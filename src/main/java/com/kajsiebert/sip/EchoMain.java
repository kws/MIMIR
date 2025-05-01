package com.kajsiebert.sip;
import com.kajsiebert.sip.examples.Echo;

import org.mjsip.config.OptionParser;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.ServiceConfig;
import org.mjsip.pool.PortConfig;
import org.mjsip.ua.streamer.LoopbackStreamerFactory;
import org.mjsip.sip.address.SipURI;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.ua.MediaConfig;
import org.mjsip.ua.UserAgent;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentListenerAdapter;
import org.mjsip.ua.MediaAgent;
import org.mjsip.sip.message.SipMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class to run the Echo example.
 */
public class EchoMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(EchoMain.class);
    
    public static void main(String[] args) {
        try {
            // Create configurations
            SipConfig sipConfig = new SipConfig();
            UAConfig uaConfig = new UAConfig();
            SchedulerConfig schedulerConfig = new SchedulerConfig();
            PortConfig portConfig = new PortConfig();
            portConfig.setMediaPort(12500);  // Set first RTP port to match Asterisk
            portConfig.setPortCount(100);    // Set number of ports to allocate
            ServiceConfig serviceConfig = new ServiceConfig();
            MediaConfig mediaConfig = new MediaConfig();
            
            // Set registrar
            SipURI registrar = new SipURI("macmini02.lan", 5060);
            uaConfig.setRegistrar(registrar);
            
            // Set media specifications
            MediaSpec[] mediaSpecs = new MediaSpec[] {
                new MediaSpec(0, "PCMU", 8000, 1, 160),  // G.711 u-law
                new MediaSpec(8, "PCMA", 8000, 1, 160)   // G.711 A-law
            };
            MediaDesc[] mediaDescs = new MediaDesc[] {
                new MediaDesc("audio", 0, "RTP/AVP", mediaSpecs)
            };
            mediaConfig.setMediaDescs(mediaDescs);
            
            // Parse command line options
            OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, portConfig, serviceConfig, mediaConfig);
            
            // Normalize configurations
            sipConfig.normalize();
            uaConfig.normalize(sipConfig);
            
            // Create components
            ConfiguredScheduler scheduler = new ConfiguredScheduler(schedulerConfig);
            SipProvider sipProvider = new SipProvider(sipConfig, scheduler);
            
            // Create streamer factory
            LoopbackStreamerFactory streamerFactory = new LoopbackStreamerFactory();
            
            // Create and start echo server
            new Echo(
                sipProvider,
                streamerFactory,
                uaConfig,
                portConfig.createPool(),
                false,  // force_reverse_route
                serviceConfig
            ) {
                @Override
                protected UserAgentListener createCallHandler(SipMessage msg) {
                    return new UserAgentListenerAdapter() {
                        @Override
                        public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] media_descs) {
                            // Use our configured media specs instead of remote ones
                            MediaAgent mediaAgent = new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory);
                            ua.accept(mediaAgent);
                            LOGGER.info("incoming call accepted with configured media specs");
                        }
                    };
                }
            };
            
            LOGGER.info("Echo server started and listening on {}", sipProvider.getViaAddress());
            
            // Keep running until interrupted
            while (true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            LOGGER.error("Error starting echo server", e);
            System.exit(1);
        }
    }
} 