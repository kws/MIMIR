package com.sip.client;

import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.ua.ServiceIdentity;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SIP client that registers with an Asterisk server and handles incoming calls.
 * This client intercepts the RTP audio streams for external processing before 
 * returning them to the caller.
 */
public class SipClient implements UserAgentListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SipClient.class);

    private SipProvider sipProvider;
    private UserAgent userAgent;
    private UserAgentProfile userProfile;
    private CustomMediaStreamer mediaStreamer;
    private AudioProcessor audioProcessor;
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
            
            // Set up SIP provider
            sipProvider = new SipProvider(config.getLocalAddress(), config.getLocalPort());
            
            // Configure user profile
            userProfile = new UserAgentProfile();
            userProfile.setUserName(config.getUsername());
            userProfile.setRealm(config.getDomain());
            userProfile.setPasswd(config.getPassword());
            userProfile.setAuthUser(config.getAuthUsername());
            userProfile.setOutboundProxy(config.getProxyAddress() + ":" + config.getProxyPort());
            userProfile.setRegistrar(config.getRegistrarAddress() + ":" + config.getRegistrarPort());
            userProfile.setExpires(config.getRegisterExpires());
            userProfile.setHangupTime(0); // Never automatically hangup
            userProfile.setLoopback(false);
            userProfile.setNoOffer(false);
            userProfile.setMediaPort(config.getMediaPort());
            userProfile.setVideoPort(config.getVideoPort());
            userProfile.setAudioCodecNames(config.getAudioCodecs());
            userProfile.setDefaultTransport(config.getTransport());
            
            // Create audio processor
            audioProcessor = new AudioProcessor();
            
            // Create custom media streamer
            mediaStreamer = new CustomMediaStreamer(userProfile, audioProcessor);
            
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
            
            // Create and start user agent for handling SIP calls
            SipURI from = new SipURI(userProfile.getUserName(), userProfile.getRealm());
            NameAddress fromNameAddress = new NameAddress(from);
            ServiceIdentity serviceId = new ServiceIdentity(fromNameAddress);
            
            userAgent = new UserAgent(sipProvider, userProfile, this, mediaStreamer);
            
            // Register with the server
            if (userProfile.doRegister()) {
                LOGGER.info("Registering with SIP server: {}", userProfile.getRegistrar());
                userAgent.register();
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
        if (mediaStreamer != null) {
            mediaStreamer.setAudioProcessor(audioProcessor);
        }
    }

    @Override
    public void onUaRegistrationSuccess(UserAgent ua, NameAddress target, NameAddress contact, String result) {
        LOGGER.info("Successfully registered with server: {}", result);
        registered = true;
    }

    @Override
    public void onUaRegistrationFailure(UserAgent ua, NameAddress target, String result) {
        LOGGER.error("Registration failed: {}", result);
        registered = false;
    }

    @Override
    public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, String sdp, UserAgent.SessionDescriptor session) {
        LOGGER.info("Incoming call from {}", caller);
        try {
            // Auto answer the call
            mediaStreamer.prepareSession();
            ua.accept();
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
        mediaStreamer.closeSession();
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
