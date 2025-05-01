package com.kajsiebert.sip.openai;

import java.util.concurrent.Executor;

import org.kohsuke.args4j.Option;
import org.mjsip.config.OptionParser;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.header.RouteHeader;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.message.SipResponses;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipId;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.sip.transaction.TransactionClient;
import org.mjsip.sip.transaction.TransactionServer;
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
import org.slf4j.LoggerFactory;


public class OpenAIRealtimeUserAgent extends RegisteringMultipleUAS {

    private final StreamerFactory _streamerFactory;

    /** 
	 * Creates a {@link OpenAIRealtimeUserAgent} service. 
	 */
	public OpenAIRealtimeUserAgent(
        SipProvider sip_provider, 
        StreamerFactory streamerFactory, 
        UAConfig uaConfig, 
        PortPool portPool,
        boolean force_reverse_route, 
        ServiceOptions serviceConfig
    ) {

        super(sip_provider,portPool, uaConfig, serviceConfig);

        _streamerFactory = streamerFactory;

        sip_provider.addSelectiveListener(SipId.createMethodId(SipMethods.MESSAGE),this); 
    }

    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        MediaConfig mediaConfig = new MediaConfig();
		MediaSpec[] mediaSpecs = new MediaSpec[] {
			new MediaSpec(0, "PCMU", 8000, 1, 160),  // G.711 u-law
			new MediaSpec(8, "PCMA", 8000, 1, 160)   // G.711 A-law
		};
		MediaDesc[] mediaDescs = new MediaDesc[] {
			new MediaDesc("audio", 0, "RTP/AVP", mediaSpecs)
		};
		mediaConfig.setMediaDescs(mediaDescs);

        OpenAIRealtimeClient client = new OpenAIRealtimeClient();

        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] media_descs) {
                client.start();
                StreamerFactory streamerFactory = new StreamerFactory() {
                    @Override
                    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flow_spec) {
                        return new OpenAIMediaStreamer(client, flow_spec);
                    }
                };
                ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory));
            }
        };
    }

}
