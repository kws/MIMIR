package com.kajsiebert.sip.openai;

import java.io.IOException;

import org.mjsip.config.OptionParser;
import org.mjsip.media.MediaDesc;
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
import org.mjsip.ua.RegisteringMultipleUAS;
import org.mjsip.ua.ServiceConfig;
import org.mjsip.ua.ServiceOptions;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentListenerAdapter;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kajsiebert.sip.openai.util.OptionsListener;

public class OpenAIRealtimeUserAgent extends RegisteringMultipleUAS {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeUserAgent.class);

    private static final OptionsListener optionsListener = new OptionsListener();

    /** 
	 * Creates a {@link OpenAIRealtimeUserAgent} service. 
	 */
    private final Vertx vertx;
    private final ExtensionConfigManager extConfigManager;
    
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
        sip_provider.addSelectiveListener(SipId.createMethodId(SipMethods.OPTIONS), optionsListener);
        this.vertx = vertx;
        this.extConfigManager = extConfigManager;
    }


    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        String lastExtensionCalled = msg.getHeader("X-Called-Extension").getValue();
        final ExtensionConfig cfg = extConfigManager.getConfig(lastExtensionCalled);

        LOG.debug("Creating call handler for extension: {}", lastExtensionCalled);

        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] media_descs) {
                final OpenAICallController streamer = new OpenAICallController(OpenAIRealtimeUserAgent.this.vertx, ua, cfg);
                streamer.awaitCallHandled(30);
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