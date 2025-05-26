package com.kajsiebert.sip.openai;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.mjsip.config.OptionParser;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipId;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.MediaConfig;
import org.mjsip.ua.RegisteringMultipleUAS;
import org.mjsip.ua.ServiceConfig;
import org.mjsip.ua.ServiceOptions;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentListenerAdapter;
import org.mjsip.ua.streamer.StreamerFactory;
import io.vertx.core.Vertx;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAIRealtimeUserAgent extends RegisteringMultipleUAS {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeUserAgent.class);

    /** 
	 * Creates a {@link OpenAIRealtimeUserAgent} service. 
	 */
    private final Vertx vertx;
    private final ExtensionConfigManager extConfigManager;
    private String lastExtensionCalled;
    
    public OpenAIRealtimeUserAgent(
        SipProvider sip_provider, 
        UAConfig uaConfig, 
        PortPool portPool,
        boolean force_reverse_route, 
        ServiceOptions serviceConfig,
        Vertx vertx,
        ExtensionConfigManager extConfigManager
    ) {

        super(sip_provider,portPool, uaConfig, serviceConfig);
        sip_provider.addSelectiveListener(SipId.createMethodId(SipMethods.MESSAGE), this);
        this.vertx = vertx;
        this.extConfigManager = extConfigManager;
    }

    @Override
	protected void onInviteReceived(SipMessage msg) {
        this.lastExtensionCalled = msg.getHeader("X-Called-Extension").getValue();
        super.onInviteReceived(msg);
    }

    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        MediaConfig mediaConfig = new MediaConfig();
		MediaSpec[] mediaSpecs = new MediaSpec[] {
			new MediaSpec(0, "PCMU", 8000, 1, 160),  // G.711 u-law
			// new MediaSpec(8, "PCMA", 8000, 1, 160)   // G.711 A-law
		};
		MediaDesc[] mediaDescs = new MediaDesc[] {
			new MediaDesc("audio", 0, "RTP/AVP", mediaSpecs)
		};
		mediaConfig.setMediaDescs(mediaDescs);
        final String lastExtensionCalled = this.lastExtensionCalled;

        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] media_descs) {
                final ExtensionConfig cfg = extConfigManager.getConfig(lastExtensionCalled);
                StreamerFactory streamerFactory = new StreamerFactory() {
                    @Override
                    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flow_spec) {
                        return new VertxMediaStreamer(OpenAIRealtimeUserAgent.this.vertx, ua, flow_spec, cfg);
                    }
                };
                VertxMediaStreamer.warmup(OpenAIRealtimeUserAgent.this.vertx, cfg)
                    .onComplete(ar -> ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory)));
            }
        };
    }


	/** The main method. */
	public static void main(String[] args) {
		System.out.println("Echo "+SipStack.version);

		SipConfig sipConfig = new SipConfig();
		UAConfig uaConfig = new UAConfig();
		SchedulerConfig schedulerConfig = new SchedulerConfig();
		PortConfig portConfig = new PortConfig();
        ServiceConfig serviceConfig = new ServiceConfig();
		
		OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, portConfig, serviceConfig);
		
		sipConfig.normalize();
		uaConfig.normalize(sipConfig);
		
        // Load extension configuration or fallback to default
        ExtensionConfigManager extConfigManager;
        try {
            extConfigManager = ExtensionConfigManager.load("extensions.yml");
        } catch (IOException e) {
            System.err.println("Error loading extension configuration: " + e.getMessage());
            System.exit(1);
            return;
        }
        // Initialize a single shared Vert.x instance for all sessions
        Vertx vertx = Vertx.vertx();
        new OpenAIRealtimeUserAgent(
            new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig)),
            uaConfig,
            portConfig.createPool(),
            false,
            serviceConfig,
            vertx,
            extConfigManager
        );

		// Prompt before exit
		try {
			System.out.println("press 'enter' to exit");
				(new java.io.BufferedReader(new java.io.InputStreamReader(System.in))).readLine();
			System.exit(0);
		}
		catch (Exception e) {}
	}   

}