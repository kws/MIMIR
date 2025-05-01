package com.sip.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interface for processing audio data from RTP streams.
 * This class provides methods for external applications to process
 * audio data and return it for transmission back to the caller.
 */
public class AudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);
    
    // Queues for incoming and outgoing audio data
    private BlockingQueue<byte[]> incomingAudioQueue;
    private BlockingQueue<byte[]> outgoingAudioQueue;
    
    // Flags for processing state
    private volatile boolean processing;
    private volatile boolean passthrough;
    
    // Statistics
    private long packetsProcessed;
    private long bytesProcessed;
    
    /**
     * Create a new audio processor.
     */
    public AudioProcessor() {
        incomingAudioQueue = new LinkedBlockingQueue<>();
        outgoingAudioQueue = new LinkedBlockingQueue<>();
        processing = false;
        passthrough = true; // Default to pass-through mode
        packetsProcessed = 0;
        bytesProcessed = 0;
    }
    
    /**
     * Start processing audio data.
     */
    public void startProcessing() {
        LOGGER.info("Starting audio processing");
        processing = true;
        packetsProcessed = 0;
        bytesProcessed = 0;
    }
    
    /**
     * Stop processing audio data.
     */
    public void stopProcessing() {
        LOGGER.info("Stopping audio processing");
        processing = false;
        incomingAudioQueue.clear();
        outgoingAudioQueue.clear();
        LOGGER.info("Processed {} packets, {} bytes", packetsProcessed, bytesProcessed);
    }
    
    /**
     * Set whether to use pass-through mode (no processing).
     * 
     * @param passthrough true to enable pass-through, false to process audio
     */
    public void setPassthrough(boolean passthrough) {
        this.passthrough = passthrough;
        LOGGER.info("Passthrough mode: {}", passthrough);
    }
    
    /**
     * Process incoming audio data from the RTP stream.
     * 
     * @param audioData The audio data to process
     * @return The processed audio data to send back
     */
    public byte[] processAudio(byte[] audioData) {
        if (!processing) {
            return audioData;
        }
        
        // In pass-through mode, just return the audio data
        if (passthrough) {
            return audioData;
        }
        
        try {
            // Add the audio data to the incoming queue
            incomingAudioQueue.offer(audioData, 100, TimeUnit.MILLISECONDS);
            
            // Try to get processed audio data from the outgoing queue
            byte[] processedData = outgoingAudioQueue.poll(100, TimeUnit.MILLISECONDS);
            
            // If no processed data is available, return the original data
            if (processedData == null) {
                return audioData;
            }
            
            // Update statistics
            packetsProcessed++;
            bytesProcessed += audioData.length;
            
            return processedData;
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while processing audio", e);
            return audioData;
        }
    }
    
    /**
     * Get incoming audio data for processing by an external application.
     * 
     * @param timeout The timeout in milliseconds to wait for data
     * @return The audio data, or null if no data is available
     */
    public byte[] getAudioForProcessing(long timeout) {
        try {
            return incomingAudioQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while getting audio data", e);
            return null;
        }
    }
    
    /**
     * Submit processed audio data from an external application.
     * 
     * @param processedData The processed audio data
     * @return true if the data was accepted, false otherwise
     */
    public boolean submitProcessedAudio(byte[] processedData) {
        if (!processing) {
            return false;
        }
        
        try {
            return outgoingAudioQueue.offer(processedData, 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while submitting processed audio", e);
            return false;
        }
    }
    
    /**
     * Check if audio processing is active.
     * 
     * @return true if processing is active, false otherwise
     */
    public boolean isProcessing() {
        return processing;
    }
    
    /**
     * Get the number of packets waiting for processing.
     * 
     * @return The number of packets in the incoming queue
     */
    public int getIncomingQueueSize() {
        return incomingAudioQueue.size();
    }
    
    /**
     * Get the number of processed packets waiting to be sent.
     * 
     * @return The number of packets in the outgoing queue
     */
    public int getOutgoingQueueSize() {
        return outgoingAudioQueue.size();
    }
    
    /**
     * Get the total number of packets processed.
     * 
     * @return The number of packets processed
     */
    public long getPacketsProcessed() {
        return packetsProcessed;
    }
    
    /**
     * Get the total number of bytes processed.
     * 
     * @return The number of bytes processed
     */
    public long getBytesProcessed() {
        return bytesProcessed;
    }
}
