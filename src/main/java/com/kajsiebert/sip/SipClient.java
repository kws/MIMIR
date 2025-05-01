package com.kajsiebert.sip;

import org.mjsip.media.MediaDesc;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.Scheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.pool.PortPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

/**
 * SIP client that registers with an Asterisk server and handles incoming calls.
 * This client intercepts the RTP audio streams for external processing before 
 * returning them to the caller.
 */
public class SipClient implements UserAgentListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SipClient.class);

    private SipProvider sipProvider;
    private UserAgent userAgent;
    private SipOptions clientOptions;
    private RtpInterceptorFactory mediaFactory;
    private AudioProcessor audioProcessor;
    private MediaAgent mediaAgent;
    private boolean registered = false;
    private boolean callActive = false;
    private Config config;

    /**
     * Create a new SIP client with the specified configuration.
     *
     * @param config The configuration for the SIP client
     */
    public SipClient(Config config) {
        try {
            LOGGER.info("Initializing SIP client");
            
            this.config = config;
            
            // Set up SIP provider with scheduler
            Scheduler scheduler = new Scheduler(new SchedulerConfig());
            sipProvider = new SipProvider(config.getLocalAddress(), config.getLocalPort(), scheduler);
            
            // Configure user profile
            clientOptions = new SipOptions(config);
            
            // Create audio processor
            audioProcessor = new AudioProcessor();
            
            // Enable echo mode with delay
            audioProcessor.setEchoMode(true, 1000); // 1 second delay
            
            // Create custom media streamer
            mediaFactory = new RtpInterceptorFactory(audioProcessor);
            
            LOGGER.info("SIP client initialization complete");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SIP client", e);
            throw new RuntimeException("Failed to initialize SIP client", e);
        }
    }

    /**
     * Start the SIP client and register with the Asterisk server.
     */
    public void start() {
        try {
            LOGGER.info("Starting SIP client");
            
            // Create port pool for media
            PortPool portPool = new PortPool(config.getMediaPort(), config.getMediaPort() + 10);
            
            // Create user agent for handling SIP calls
            userAgent = new UserAgent(sipProvider, portPool, clientOptions, this);
            
            // Register with the server if configured
            if (!config.getDomain().isEmpty() && !config.getUsername().isEmpty() && !config.getPassword().isEmpty()) {
                LOGGER.info("Registering with SIP server: {}", config.getRegistrarAddress() + ":" + config.getRegistrarPort());
                
                // Build the SIP URI
                String authRealm = config.getDomain();
                String authUser = config.getAuthUsername() != null ? config.getAuthUsername() : config.getUsername();
                String authPasswd = config.getPassword();
                
                userAgent.register(authUser, authRealm, authPasswd);
            } else {
                LOGGER.info("Registration disabled in configuration");
            }
            
            LOGGER.info("SIP client started successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to start SIP client", e);
            throw new RuntimeException("Failed to start SIP client", e);
        }
    }

    /**
     * Stop the SIP client and unregister from the server.
     */
    public void stop() {
        try {
            LOGGER.info("Stopping SIP client");
            
            if (callActive) {
                userAgent.hangup();
                callActive = false;
            }
            
            if (registered) {
                // Unregister with the same credentials used for registration
                String authRealm = config.getDomain();
                String authUser = config.getAuthUsername() != null ? config.getAuthUsername() : config.getUsername();
                String authPasswd = config.getPassword();
                
                userAgent.unregister(authUser, authRealm, authPasswd);
                registered = false;
            }
            
            if (sipProvider != null) {
                sipProvider.halt();
            }
            
            audioProcessor.stopProcessing();
            
            LOGGER.info("SIP client stopped");
        } catch (Exception e) {
            LOGGER.error("Error stopping SIP client", e);
        }
    }

    /**
     * Set the audio processor for handling RTP audio streams.
     *
     * @param audioProcessor The audio processor to use
     */
    public void setAudioProcessor(AudioProcessor audioProcessor) {
        this.audioProcessor = audioProcessor;
        if (mediaFactory != null) {
            mediaFactory.setAudioProcessor(audioProcessor);
        }
    }
    
    /**
     * Set the echo delay for the audio processor.
     *
     * @param delayMs The delay in milliseconds
     */
    public void setEchoDelay(int delayMs) {
        if (audioProcessor != null) {
            audioProcessor.setEchoDelay(delayMs);
        }
    }
    
    /**
     * Enable or disable echo mode.
     *
     * @param enable true to enable echo mode, false to disable
     * @param delayMs The delay in milliseconds (if enabling)
     */
    public void setEchoMode(boolean enable, int delayMs) {
        if (audioProcessor != null) {
            audioProcessor.setEchoMode(enable, delayMs);
        }
    }

    @Override
    public void onUaRegistrationSucceeded(UserAgent ua, String result) {
        LOGGER.info("Successfully registered with server: {}", result);
        registered = true;
    }

    @Override
    public void onUaRegistrationFailed(UserAgent ua, String result) {
        LOGGER.error("Registration failed: {}", result);
        registered = false;
    }

    @Override
    public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] mediaDescs) {
        LOGGER.info("Incoming call from {}", caller);
        try {
            // Auto answer the call
            audioProcessor.startProcessing();
            ua.accept(mediaAgent);
            callActive = true;
            LOGGER.info("Call automatically answered");
        } catch (Exception e) {
            LOGGER.error("Failed to answer call", e);
            ua.hangup();
        }
    }

    @Override
    public void onUaCallCancelled(UserAgent ua) {
        LOGGER.info("Call cancelled");
        callActive = false;
    }

    @Override
    public void onUaCallProgress(UserAgent ua) {
        LOGGER.info("Call in progress");
    }

    @Override
    public void onUaCallRinging(UserAgent ua) {
        LOGGER.info("Call ringing");
    }

    @Override
    public void onUaCallAccepted(UserAgent ua) {
        LOGGER.info("Call accepted");
        callActive = true;
    }

    @Override
    public void onUaCallIncomingAccepted(UserAgent ua) {
        LOGGER.info("Incoming call accepted");
        callActive = true;
    }

    @Override
    public void onUaIncomingCallTimeout(UserAgent ua) {
        LOGGER.info("Incoming call timeout");
    }

    @Override
    public void onUaCallConfirmed(UserAgent ua) {
        LOGGER.info("Call confirmed");
    }

    @Override
    public void onUaCallRedirected(UserAgent ua, NameAddress redirectTo) {
        LOGGER.info("Call redirected to: {}", redirectTo);
    }

    @Override
    public void onUaCallTransferred(UserAgent ua) {
        LOGGER.info("Call transferred");
    }

    @Override
    public void onUaCallFailed(UserAgent ua, String reason) {
        LOGGER.error("Call failed: {}", reason);
        callActive = false;
    }

    @Override
    public void onUaCallClosed(UserAgent ua) {
        LOGGER.info("Call closed");
        callActive = false;
        audioProcessor.stopProcessing();
    }

    @Override
    public void onUaMediaSessionStarted(UserAgent ua, String type, String codec) {
        LOGGER.info("Media session started with codec: {}", codec);
    }

    @Override
    public void onUaMediaSessionStopped(UserAgent ua, String type) {
        LOGGER.info("Media session stopped: {}", type);
    }

    /**
     * Check if the client is registered with the server.
     * 
     * @return true if registered, false otherwise
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Check if a call is currently active.
     * 
     * @return true if a call is active, false otherwise
     */
    public boolean isCallActive() {
        return callActive;
    }
    
    /**
     * Get statistics about the audio processing.
     * 
     * @return A string with statistics
     */
    public String getAudioStats() {
        if (audioProcessor == null) {
            return "Audio processor not available";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Audio processor statistics:\n");
        sb.append("  Processing: ").append(audioProcessor.isProcessing()).append("\n");
        sb.append("  Passthrough: ").append(audioProcessor.isPassthrough()).append("\n");
        sb.append("  Echo mode: ").append(audioProcessor.isEchoMode()).append("\n");
        
        if (audioProcessor.isEchoMode()) {
            sb.append("  Echo delay: ").append(audioProcessor.getEchoDelay()).append(" ms\n");
            sb.append("  Echo buffer size: ").append(audioProcessor.getEchoBufferSize()).append("\n");
        }
        
        sb.append("  Packets processed: ").append(audioProcessor.getPacketsProcessed()).append("\n");
        sb.append("  Bytes processed: ").append(audioProcessor.getBytesProcessed()).append("\n");
        
        return sb.toString();
    }
}