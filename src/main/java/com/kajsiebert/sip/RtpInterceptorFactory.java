package com.kajsiebert.sip;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Factory for creating RTP interceptors that process audio data.
 * This class implements the StreamerFactory interface from mjSIP
 * to intercept and process RTP audio packets.
 */
public class RtpInterceptorFactory implements StreamerFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtpInterceptorFactory.class);
    
    private AudioProcessor audioProcessor;
    private Map<String, RtpMediaStreamer> activeStreamers;
    
    /**
     * Create a new RTP interceptor factory.
     * 
     * @param audioProcessor The audio processor to use
     */
    public RtpInterceptorFactory(AudioProcessor audioProcessor) {
        this.audioProcessor = audioProcessor;
        this.activeStreamers = new HashMap<>();
    }
    
    /**
     * Set the audio processor for this factory.
     * 
     * @param audioProcessor The audio processor to use
     */
    public void setAudioProcessor(AudioProcessor audioProcessor) {
        this.audioProcessor = audioProcessor;
        
        // Update all active streamers
        for (RtpMediaStreamer streamer : activeStreamers.values()) {
            streamer.setAudioProcessor(audioProcessor);
        }
    }
    
    @Override
    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flowSpec) {
        LOGGER.info("Creating media streamer for: {} - {}", flowSpec.getMediaType(), flowSpec.getDirection());
        
        // Only handle audio streams
        if (!flowSpec.getMediaType().equals("audio")) {
            LOGGER.info("Ignoring non-audio media type: {}", flowSpec.getMediaType());
            return null;
        }
        
        // Create a new interceptor
        RtpMediaStreamer streamer = new RtpMediaStreamer(executor, flowSpec, audioProcessor);
        String id = flowSpec.getMediaType() + "-" + flowSpec.getDirection();
        activeStreamers.put(id, streamer);
        
        LOGGER.info("Created RTP interceptor: {}, local port: {}, remote: {}:{}", 
                id, flowSpec.getLocalPort(), flowSpec.getRemoteAddress(), flowSpec.getRemotePort());
                
        return streamer;
    }
    
    /**
     * Stop all active streamers.
     */
    public void stopAll() {
        LOGGER.info("Stopping all RTP interceptors");
        
        for (RtpMediaStreamer streamer : activeStreamers.values()) {
            streamer.halt();
        }
        
        activeStreamers.clear();
    }
    
    /**
     * Get the number of active streamers.
     * 
     * @return The number of active streamers
     */
    public int getActiveStreamerCount() {
        return activeStreamers.size();
    }
    
    /**
     * Get all active streamers.
     * 
     * @return The map of active streamers
     */
    public Map<String, RtpMediaStreamer> getActiveStreamers() {
        return activeStreamers;
    }
}