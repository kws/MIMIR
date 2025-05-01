package com.kajsiebert.sip;

import org.mjsip.config.OptionParser;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.media.MediaDesc;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.RegisteringUserAgent;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.streamer.LoopbackStreamerFactory;
import org.mjsip.pool.PortPool;
import org.mjsip.ua.registration.RegistrationClient;
import org.mjsip.ua.registration.RegistrationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple SIP client that registers with a server and handles incoming calls.
 * This version does not process audio, it just accepts calls and uses loopback audio.
 */
public class SimpleSipClient implements UserAgentListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleSipClient.class);
    
    private final RegisteringUserAgent userAgent;
    private boolean registered = false;
    private boolean callActive = false;
    
    /**
     * Create a new simple SIP client.
     * 
     * @param sipProvider The SIP provider
     * @param portPool The port pool for RTP
     * @param uaConfig The user agent configuration
     */
    public SimpleSipClient(SipProvider sipProvider, PortPool portPool, UAConfig uaConfig) {
        // Create user agent with loopback audio
        userAgent = new RegisteringUserAgent(sipProvider, portPool, uaConfig, this);
    }
    
    /**
     * Start the client and register with the server.
     */
    public void start() {
        // Register for 1 hour, renew every 30 minutes, no keepalive
        userAgent.loopRegister(3600, 1800, 0);
    }
    
    /**
     * Stop the client and unregister from the server.
     */
    public void stop() {
        userAgent.unregister();
    }
    
    @Override
    public void onUaRegistrationSucceeded(UserAgent ua, String result) {
        LOGGER.info("Registration succeeded: {}", result);
        registered = true;
    }
    
    @Override
    public void onUaRegistrationFailed(UserAgent ua, String result) {
        LOGGER.error("Registration failed: {}", result);
        registered = false;
    }
    
    @Override
    public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] mediaDescs) {
        LOGGER.info("Incoming call from {} to {}", caller, callee);
        // Accept call with loopback audio
        MediaAgent mediaAgent = new MediaAgent(mediaDescs, new LoopbackStreamerFactory());
        ua.accept(mediaAgent);
        callActive = true;
    }
    
    @Override
    public void onUaCallIncomingAccepted(UserAgent ua) {
        LOGGER.info("Call accepted");
        callActive = true;
    }
    
    @Override
    public void onUaIncomingCallTimeout(UserAgent ua) {
        LOGGER.info("Call timeout");
        callActive = false;
    }
    
    @Override
    public void onUaCallCancelled(UserAgent ua) {
        LOGGER.info("Call cancelled");
        callActive = false;
    }
    
    @Override
    public void onUaCallConfirmed(UserAgent ua) {
        LOGGER.info("Call confirmed");
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
    }
    
    @Override
    public void onUaCallRedirected(UserAgent ua, NameAddress redirect_to) {
        LOGGER.info("Call redirected to {}", redirect_to);
    }
    
    @Override
    public void onUaMediaSessionStarted(UserAgent ua, String type, String codec) {
        LOGGER.info("Media session started: {} {}", type, codec);
    }
    
    @Override
    public void onUaMediaSessionStopped(UserAgent ua, String type) {
        LOGGER.info("Media session stopped: {}", type);
    }
    
    /**
     * Check if the client is registered with the server.
     * 
     * @return true if registered
     */
    public boolean isRegistered() {
        return registered;
    }
    
    /**
     * Check if a call is active.
     * 
     * @return true if a call is active
     */
    public boolean isCallActive() {
        return callActive;
    }
} 