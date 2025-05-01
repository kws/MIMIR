package com.kajsiebert.sip;

import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.RegisteringUserAgent;
import org.mjsip.ua.UAOptions;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.pool.PortPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SIP client that registers with an Asterisk server and handles incoming calls.
 * This client intercepts the RTP audio streams for external processing before 
 * returning them to the caller.
 */
public class SipClient implements UserAgentListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SipClient.class);

    private final SipProvider sipProvider;
    private final RegisteringUserAgent userAgent;
    private final UAOptions clientOptions;
    private final RtpInterceptorFactory mediaFactory;
    private final AudioProcessor audioProcessor;
    private boolean registered = false;
    private boolean callActive = false;
    private final ConfiguredScheduler scheduler;
    private final PortPool portPool;

    /**
     * Create a new SIP client.
     */
    public SipClient(UAOptions config) {
        this.clientOptions = config;
        this.scheduler = new ConfiguredScheduler(new SchedulerConfig());
        this.portPool = new PortPool(5060, 5070); // Use default port range
        this.sipProvider = new SipProvider(config, scheduler);
        this.audioProcessor = new AudioProcessor();
        this.mediaFactory = new RtpInterceptorFactory(audioProcessor);
        
        // Create the user agent with the correct constructor
        this.userAgent = new RegisteringUserAgent(sipProvider, portPool, config, this);
    }

    /**
     * Start the SIP client and register with the server if configured.
     */
    public void start() {
        LOGGER.info("Starting SIP client");
        if (clientOptions.isRegister()) {
            LOGGER.info("Registering with SIP server");
            userAgent.loopRegister(clientOptions.getExpires(), clientOptions.getExpires()/2, clientOptions.getKeepAliveTime());
        }
    }

    /**
     * Stop the SIP client and unregister from the server.
     */
    public void stop() {
        LOGGER.info("Stopping SIP client");
        if (registered) {
            userAgent.unregister();
        }
    }

    /**
     * Make an outgoing call.
     */
    public void call(String callee) {
        LOGGER.info("Making outgoing call to {}", callee);
        MediaDesc[] mediaDescs = new MediaDesc[] {
            new MediaDesc("audio", 0, "RTP/AVP", new MediaSpec[] {
                new MediaSpec(8, "PCMA", 8000, 1)  // A-law PCM
            })
        };
        MediaAgent mediaAgent = new MediaAgent(mediaDescs, mediaFactory);
        userAgent.call(callee, mediaAgent);
    }

    // UserAgentListener implementation

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
        MediaAgent mediaAgent = new MediaAgent(mediaDescs, mediaFactory);
        ua.accept(mediaAgent);
    }

    @Override
    public void onUaCallIncomingAccepted(UserAgent ua) {
        LOGGER.info("Incoming call accepted");
        callActive = true;
    }

    @Override
    public void onUaCallCancelled(UserAgent ua) {
        LOGGER.info("Call cancelled");
        callActive = false;
    }

    @Override
    public void onUaCallClosed(UserAgent ua) {
        LOGGER.info("Call closed");
        callActive = false;
    }

    @Override
    public void onUaCallFailed(UserAgent ua, String reason) {
        LOGGER.error("Call failed: {}", reason);
        callActive = false;
    }

    @Override
    public void onUaCallRedirected(UserAgent ua, NameAddress redirect_to) {
        LOGGER.info("Call redirected to {}", redirect_to);
    }

    @Override
    public void onUaCallTransferred(UserAgent ua) {
        LOGGER.info("Call transferred");
    }

    @Override
    public void onUaMediaSessionStarted(UserAgent ua, String type, String codec) {
        LOGGER.info("Media session started: {} with codec {}", type, codec);
    }

    @Override
    public void onUaMediaSessionStopped(UserAgent ua, String type) {
        LOGGER.info("Media session stopped: {}", type);
    }

    @Override
    public void onUaIncomingCallTimeout(UserAgent ua) {
        LOGGER.info("Incoming call timeout");
    }
}