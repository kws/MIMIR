package com.kajsiebert.sip;

import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.RegisteringUserAgent;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UAConfig;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.pool.PortPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple main class to test the SIP client.
 */
public class SimpleMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleMain.class);
    
    public static void main(String[] args) {
        // Create SIP configuration
        SipConfig sipConfig = new SipConfig();
        sipConfig.setDefaultExpires(3600); // 1 hour
        sipConfig.setTransportProtocols(new String[] {"udp"});
        sipConfig.setHostPort(5060);
        sipConfig.normalize();
        
        // Create UA configuration
        UAConfig uaConfig = new UAConfig();
        uaConfig.setSipUser("nopass");
        uaConfig.setAuthRealm("macmini02.lan");
        uaConfig.setAuthPasswd("");
        uaConfig.setRegistrar(new SipURI("macmini02.lan", 5060));
        uaConfig.setRegister(true);  // Enable registration
        uaConfig.setUaServer(true);  // Enable UA server mode
        uaConfig.setOptionsServer(true);  // Enable OPTIONS server
        uaConfig.setNullServer(true);  // Enable NULL server
        uaConfig.normalize(sipConfig);
        
        // Create scheduler
        ConfiguredScheduler scheduler = new ConfiguredScheduler(new SchedulerConfig());
        
        // Create SIP provider
        SipProvider sipProvider = new SipProvider(sipConfig, scheduler);
        
        // Create port pool for RTP
        PortPool portPool = new PortPool(10000, 20000);
        
        // Create and start client
        SimpleSipClient client = new SimpleSipClient(sipProvider, portPool, uaConfig);
        client.start();
        
        LOGGER.info("SIP client started. Waiting for calls...");
        
        // Keep the program running
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            LOGGER.info("Shutting down...");
            client.stop();
        }
    }
} 