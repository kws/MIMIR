package com.kajsiebert.sip;

import org.mjsip.media.MediaDesc;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.provider.SipOptions;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.StaticOptions;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
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
    private StaticOptions userProfile;
    private RtpInterceptorFactory mediaFactory;
    private AudioProcessor audioProcessor;
    private MediaAgent mediaAgent;
    private boolean registered = false;
    private boolean callActive = false;

    /**
     * Create a new SIP client with the specified configuration.
     *
     * @param config The configuration for the SIP client
     */
    public SipClient(Config config) {
        try {
            LOGGER.info("Initializing SIP client");
            
            // Set up SIP provider with options
            SipOptions options = new SipOptions();
            options.setLocalPort(config.getLocalPort());
            options.setBindAddr(config.getLocalAddress());
            sipProvider = new SipProvider(options);
            
            // Configure user profile
            userProfile = new StaticOptions();
            userProfile.setUserName(config.getUsername());
            userProfile.setRealm(config.getDomain());
            userProfile.setPassword(config.getPassword());
            userProfile.setAuthUser(config.getAuthUsername());
            userProfile.setOutboundProxy(config.getProxyAddress() + ":" + config.getProxyPort());
            userProfile.setRegistrar(config.getRegistrarAddress() + ":" + config.getRegistrarPort());
            userProfile.setExpires(config.getRegisterExpires());
            userProfile.setAudioPort(config.getMediaPort());
            userProfile.setVideoPort(config.getVideoPort());
            userProfile.setAudioCodecNames(config.getAudioCodecs());
            userProfile.setDefaultTransport(config.getTransport());
            
            // Create audio processor
            audioProcessor = new AudioProcessor();
            
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
            
            // Create user agent for handling SIP calls
            userAgent = new UserAgent(sipProvider, this);
            
            // Create the media agent
            mediaAgent = userAgent.createMediaAgent(userProfile, mediaFactory);
            
            // Register with the server
            if (userProfile.mustRegister()) {
                LOGGER.info("Registering with SIP server: {}", userProfile.getRegistrarUri());
                userAgent.register(userProfile);
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
                userAgent.unregister();
                registered = false;
            }
            
            if (sipProvider != null) {
                sipProvider.halt();
            }
            
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
}