package com.kajsiebert.sip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processor for audio data received from RTP packets.
 * This class handles the processing of audio data from the RTP
 * streams, allowing for external processing of the audio.
 * 
 * In echo mode, it adds a configurable delay to the audio.
 */
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    
    // Queue sizes
    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final int MAX_QUEUE_SIZE = 1000;
    
    // Queues for audio data
    private BlockingQueue<byte[]> incomingQueue;
    private BlockingQueue<byte[]> outgoingQueue;
    
    // Processing thread
    private Thread processingThread;
    
    // Control flags
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean passthrough = new AtomicBoolean(true);
    private final AtomicBoolean echoMode = new AtomicBoolean(false);
    private final AtomicInteger echoDelayMs = new AtomicInteger(500); // default 500ms delay
    private final AtomicInteger echoPacketBuffer = new AtomicInteger(10); // default buffer size
    
    // Echo buffer
    private Queue<byte[]> echoBuffer;
    private long lastPacketTimestamp = 0;
    private int packetIntervalMs = 20; // assume 20ms per packet by default (common in RTP)
    
    // Statistics
    private final AtomicLong packetsProcessed = new AtomicLong(0);
    private final AtomicLong bytesProcessed = new AtomicLong(0);
    
    /**
     * Create a new audio processor with default settings.
     */
    public AudioProcessor() {
        incomingQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        outgoingQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        echoBuffer = new LinkedList<>();
    }
    
    /**
     * Process audio data from an RTP packet.
     * 
     * @param data The audio data to process
     * @return The processed audio data
     */
    public byte[] processAudio(byte[] data) {
        try {
            if (passthrough.get()) {
                // In passthrough mode, return the original data
                packetsProcessed.incrementAndGet();
                bytesProcessed.addAndGet(data.length);
                return data;
            } else if (echoMode.get()) {
                // In echo mode, add to echo buffer and return delayed packet
                return processEcho(data);
            } else {
                // In processing mode, add to the incoming queue
                if (incomingQueue.offer(data)) {
                    // Get processed data from the outgoing queue
                    byte[] processedData = outgoingQueue.poll();
                    if (processedData != null) {
                        packetsProcessed.incrementAndGet();
                        bytesProcessed.addAndGet(data.length);
                        return processedData;
                    } else {
                        LOGGER.debug("No processed data available, using original");
                        return data;
                    }
                } else {
                    LOGGER.debug("Incoming queue full, using passthrough");
                    return data;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing audio data", e);
            return data;
        }
    }
    
    /**
     * Process audio data in echo mode with delay.
     * 
     * @param data The audio data to process
     * @return The delayed audio data
     */
    private byte[] processEcho(byte[] data) {
        long now = System.currentTimeMillis();
        
        // Calculate packet interval if needed
        if (lastPacketTimestamp > 0) {
            long interval = now - lastPacketTimestamp;
            if (interval > 0 && interval < 100) { // sanity check for interval
                // Use exponential smoothing to update packet interval
                packetIntervalMs = (int)((packetIntervalMs * 0.9) + (interval * 0.1));
            }
        }
        lastPacketTimestamp = now;
        
        // Add the packet to the echo buffer
        echoBuffer.add(data.clone()); // clone to avoid data being modified
        
        // Calculate how many packets to buffer for the desired delay
        int targetBufferSize = echoDelayMs.get() / packetIntervalMs;
        if (targetBufferSize < 1) targetBufferSize = 1;
        
        // If we have enough packets buffered, return the oldest one
        byte[] result;
        if (echoBuffer.size() > targetBufferSize) {
            result = echoBuffer.remove();
        } else {
            // Buffer not full yet, return silence
            result = generateSilence(data.length);
        }
        
        packetsProcessed.incrementAndGet();
        bytesProcessed.addAndGet(data.length);
        
        return result;
    }
    
    /**
     * Generate a silent audio packet of the given length.
     * 
     * @param length The length of the packet
     * @return A silent audio packet
     */
    private byte[] generateSilence(int length) {
        byte[] silence = new byte[length];
        // For most audio codecs, all zeros represent silence
        // Some codecs use a specific value like 0x7F, but zeros usually work
        return silence;
    }
    
    /**
     * Start audio processing.
     */
    public void startProcessing() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("Audio processor already started");
            return;
        }
        
        LOGGER.info("Starting audio processor");
        
        // Clear any existing data
        incomingQueue.clear();
        outgoingQueue.clear();
        echoBuffer.clear();
        lastPacketTimestamp = 0;
        
        // Start the processing thread
        processingThread = new Thread(this::processingLoop, "AudioProcessor");
        processingThread.start();
        
        if (echoMode.get()) {
            LOGGER.info("Audio processor started in echo mode with delay: {}ms", echoDelayMs.get());
        } else if (passthrough.get()) {
            LOGGER.info("Audio processor started in passthrough mode");
        } else {
            LOGGER.info("Audio processor started in processing mode");
        }
    }
    
    /**
     * Stop audio processing.
     */
    public void stopProcessing() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        
        LOGGER.info("Stopping audio processor");
        
        // Interrupt and wait for the thread to finish
        if (processingThread != null) {
            processingThread.interrupt();
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for processing thread to finish");
            }
            processingThread = null;
        }
        
        // Clear any existing data
        incomingQueue.clear();
        outgoingQueue.clear();
        echoBuffer.clear();
        
        LOGGER.info("Audio processor stopped, processed {} packets, {} bytes",
                packetsProcessed.get(), bytesProcessed.get());
    }
    
    /**
     * Main processing loop.
     */
    private void processingLoop() {
        LOGGER.info("Audio processing thread started");
        
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // Get data from the incoming queue
                    byte[] data = incomingQueue.take();
                    
                    // Process the data
                    byte[] processedData = externalProcessing(data);
                    
                    // Add to the outgoing queue
                    if (!outgoingQueue.offer(processedData)) {
                        LOGGER.debug("Outgoing queue full, dropping processed data");
                    }
                } catch (InterruptedException e) {
                    LOGGER.info("Audio processing thread interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Error in audio processing loop", e);
                }
            }
        } finally {
            LOGGER.info("Audio processing thread finished");
        }
    }
    
    /**
     * Process audio data using external tools or APIs.
     * Currently just a placeholder for actual processing.
     * 
     * @param data The audio data to process
     * @return The processed audio data
     */
    private byte[] externalProcessing(byte[] data) {
        // This is a placeholder for external audio processing
        // In a real implementation, this method would call an external
        // API or processing tool to manipulate the audio data.
        
        // For now, just return the original data
        return data;
    }
    
    /**
     * Set passthrough mode.
     * 
     * @param passthrough true to enable passthrough, false to process audio
     */
    public void setPassthrough(boolean passthrough) {
        this.passthrough.set(passthrough);
        if (passthrough) {
            // If enabling passthrough, disable echo mode
            echoMode.set(false);
        }
        LOGGER.info("Audio processor passthrough mode: {}", passthrough);
    }
    
    /**
     * Set echo mode with delay.
     * 
     * @param enable true to enable echo mode, false to disable
     * @param delayMs the echo delay in milliseconds
     */
    public void setEchoMode(boolean enable, int delayMs) {
        if (enable) {
            // If enabling echo mode, disable passthrough
            passthrough.set(false);
            echoMode.set(true);
            echoDelayMs.set(delayMs);
            LOGGER.info("Audio processor echo mode enabled with delay: {}ms", delayMs);
        } else {
            echoMode.set(false);
            LOGGER.info("Audio processor echo mode disabled");
        }
    }
    
    /**
     * Set the echo delay.
     * 
     * @param delayMs the echo delay in milliseconds
     */
    public void setEchoDelay(int delayMs) {
        echoDelayMs.set(delayMs);
        LOGGER.info("Audio processor echo delay set to: {}ms", delayMs);
    }
    
    /**
     * Check if passthrough mode is enabled.
     * 
     * @return true if passthrough mode is enabled, false otherwise
     */
    public boolean isPassthrough() {
        return passthrough.get();
    }
    
    /**
     * Check if echo mode is enabled.
     * 
     * @return true if echo mode is enabled, false otherwise
     */
    public boolean isEchoMode() {
        return echoMode.get();
    }
    
    /**
     * Get the echo delay.
     * 
     * @return the echo delay in milliseconds
     */
    public int getEchoDelay() {
        return echoDelayMs.get();
    }
    
    /**
     * Check if the audio processor is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isProcessing() {
        return running.get();
    }
    
    /**
     * Get the size of the incoming queue.
     * 
     * @return The number of items in the incoming queue
     */
    public int getIncomingQueueSize() {
        return incomingQueue.size();
    }
    
    /**
     * Get the size of the outgoing queue.
     * 
     * @return The number of items in the outgoing queue
     */
    public int getOutgoingQueueSize() {
        return outgoingQueue.size();
    }
    
    /**
     * Get the size of the echo buffer.
     * 
     * @return The number of items in the echo buffer
     */
    public int getEchoBufferSize() {
        return echoBuffer.size();
    }
    
    /**
     * Get the number of packets processed.
     * 
     * @return The number of packets processed
     */
    public long getPacketsProcessed() {
        return packetsProcessed.get();
    }
    
    /**
     * Get the number of bytes processed.
     * 
     * @return The number of bytes processed
     */
    public long getBytesProcessed() {
        return bytesProcessed.get();
    }
}