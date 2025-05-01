package com.kajsiebert.sip;

import org.mjsip.media.MediaDesc;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.RegisteringMultipleUAS;
import org.mjsip.ua.ServiceOptions;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentListenerAdapter;
import org.mjsip.ua.streamer.LoopbackStreamerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SIP server that implements an audio echo system.
 */
public class AudioQueueServer extends RegisteringMultipleUAS {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioQueueServer.class);
    
    /**
     * Create a new audio echo server.
     * 
     * @param sipProvider The SIP provider
     * @param portPool The port pool for RTP
     * @param uaConfig The user agent configuration
     * @param serviceConfig The service configuration
     */
    public AudioQueueServer(org.mjsip.sip.provider.SipProvider sipProvider, 
            org.mjsip.pool.PortPool portPool, 
            UAConfig uaConfig, 
            ServiceOptions serviceConfig) {
        super(sipProvider, portPool, uaConfig, serviceConfig);
        LOGGER.info("AudioQueueServer initialized");
    }
    
    @Override
    protected UserAgentListener createCallHandler(org.mjsip.sip.message.SipMessage msg) {
        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] mediaDescs) {
                LOGGER.info("Incoming call from {} to {}", caller, callee);
                ua.accept(new MediaAgent(mediaDescs, new LoopbackStreamerFactory()));
            }
        };
    }
} 