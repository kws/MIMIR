package com.kajsiebert.sip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processor for audio data received from RTP packets.
 * This class handles the processing of audio data from the RTP
 * streams, allowing for external processing of the audio.
 */
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    
    // Queues for audio data
    private BlockingQueue<byte[]> incomingQueue;
    private BlockingQueue<byte[]> outgoingQueue;
    
    // Processing thread
    private Thread processingThread;
    
    // Control flags
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean passthrough = new AtomicBoolean(true);
    
    // Statistics
    private final AtomicLong packetsProcessed = new AtomicLong(0);
    private final AtomicLong bytesProcessed = new AtomicLong(0);
    
    /**
     * Create a new audio processor with default settings.
     */
    public AudioProcessor() {
        incomingQueue = new LinkedBlockingQueue<>(100);
        outgoingQueue = new LinkedBlockingQueue<>(100);
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
        
        // Start the processing thread
        processingThread = new Thread(this::processingLoop, "AudioProcessor");
        processingThread.start();
        
        LOGGER.info("Audio processor started with passthrough: {}", passthrough.get());
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
        LOGGER.info("Audio processor passthrough mode: {}", passthrough);
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