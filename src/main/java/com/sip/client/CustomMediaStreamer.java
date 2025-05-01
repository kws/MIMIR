package com.sip.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.StreamerFactory;
import org.mjsip.sdp.MediaDescriptor;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.StreamerFactory;
import org.mjsip.ua.UserAgentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom media streamer that intercepts RTP audio streams for processing.
 * This class extends the mjSIP StreamerFactory to create custom RTP interceptors
 * for audio processing.
 */
public class CustomMediaStreamer implements StreamerFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomMediaStreamer.class);
    
    private final UserAgentProfile userProfile;
    private AudioProcessor audioProcessor;
    private MediaAgent mediaAgent;
    
    // Map of active RTP interceptors
    private Map<Integer, RtpInterceptor> interceptors;
    
    /**
     * Create a new custom media streamer.
     *
     * @param userProfile The user agent profile
     * @param audioProcessor The audio processor to use
     */
    public CustomMediaStreamer(UserAgentProfile userProfile, AudioProcessor audioProcessor) {
        this.userProfile = userProfile;
        this.audioProcessor = audioProcessor;
        this.interceptors = new HashMap<>();
    }
    
    /**
     * Set the audio processor for this streamer.
     *
     * @param audioProcessor The audio processor to use
     */
    public void setAudioProcessor(AudioProcessor audioProcessor) {
        this.audioProcessor = audioProcessor;
    }
    
    /**
     * Prepare a new session for handling media.
     */
    public void prepareSession() {
        LOGGER.info("Preparing media session");
        audioProcessor.startProcessing();
        interceptors.clear();
    }
    
    /**
     * Close the current session and clean up resources.
     */
    public void closeSession() {
        LOGGER.info("Closing media session");
        
        // Stop all interceptors
        for (RtpInterceptor interceptor : interceptors.values()) {
            interceptor.stop();
        }
        
        interceptors.clear();
        audioProcessor.stopProcessing();
    }
    
    /**
     * Create a media agent for handling SDP negotiation.
     *
     * @param factory The streamer factory to use
     * @return The created media agent
     */
    public MediaAgent createMediaAgent() {
        mediaAgent = new MediaAgent(this, userProfile);
        return mediaAgent;
    }
    
    @Override
    public Object createAudioSender(SipMessage inviteMsg, SdpMessage sdpMessage, int i, MediaDescriptor md, String remoteAddr, int port) {
        LOGGER.info("Creating audio sender: {}:{}", remoteAddr, port);
        
        try {
            // Create an interceptor for sending audio
            int localPort = userProfile.getMediaPort();
            InetAddress remoteAddress = InetAddress.getByName(remoteAddr);
            
            RtpInterceptor interceptor = new RtpInterceptor(localPort, remoteAddress, port, audioProcessor);
            interceptor.start();
            
            interceptors.put(localPort, interceptor);
            
            LOGGER.info("Audio sender created on port {}", localPort);
            return interceptor;
        } catch (Exception e) {
            LOGGER.error("Failed to create audio sender", e);
            return null;
        }
    }
    
    @Override
    public Object createAudioReceiver(SipMessage inviteMsg, SdpMessage sdpMessage, int i, MediaDescriptor md, String remoteAddr, int port) {
        LOGGER.info("Creating audio receiver: {}:{}", remoteAddr, port);
        
        try {
            // Create an interceptor for receiving audio
            int localPort = userProfile.getMediaPort() + 2; // Use a different port for receiving
            InetAddress remoteAddress = InetAddress.getByName(remoteAddr);
            
            RtpInterceptor interceptor = new RtpInterceptor(localPort, remoteAddress, port, audioProcessor);
            interceptor.start();
            
            interceptors.put(localPort, interceptor);
            
            LOGGER.info("Audio receiver created on port {}", localPort);
            return interceptor;
        } catch (Exception e) {
            LOGGER.error("Failed to create audio receiver", e);
            return null;
        }
    }
    
    @Override
    public Object createVideoSender(SipMessage inviteMsg, SdpMessage sdpMessage, int i, MediaDescriptor md, String remoteAddr, int port) {
        LOGGER.info("Video sender requested but not implemented");
        return null; // Not implemented
    }
    
    @Override
    public Object createVideoReceiver(SipMessage inviteMsg, SdpMessage sdpMessage, int i, MediaDescriptor md, String remoteAddr, int port) {
        LOGGER.info("Video receiver requested but not implemented");
        return null; // Not implemented
    }
    
    @Override
    public void stopMediaStreams() {
        LOGGER.info("Stopping all media streams");
        
        // Stop all interceptors
        for (RtpInterceptor interceptor : interceptors.values()) {
            interceptor.stop();
        }
        
        interceptors.clear();
    }
    
    /**
     * Get the media agent used by this streamer.
     *
     * @return The media agent
     */
    public MediaAgent getMediaAgent() {
        return mediaAgent;
    }
    
    /**
     * Get the active RTP interceptors.
     *
     * @return The map of active interceptors
     */
    public Map<Integer, RtpInterceptor> getInterceptors() {
        return interceptors;
    }
}
