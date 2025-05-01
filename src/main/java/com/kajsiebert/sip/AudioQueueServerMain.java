package com.kajsiebert.sip;

import org.mjsip.config.OptionParser;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.ServiceConfig;
import org.mjsip.pool.PortConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class to run the AudioQueueServer.
 */
public class AudioQueueServerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioQueueServerMain.class);
    
    public static void main(String[] args) {
        try {
            // Create configurations
            SipConfig sipConfig = new SipConfig();
            UAConfig uaConfig = new UAConfig();
            SchedulerConfig schedulerConfig = new SchedulerConfig();
            PortConfig portConfig = new PortConfig();
            ServiceConfig serviceConfig = new ServiceConfig();
            
            // Parse command line options
            OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, portConfig, serviceConfig);
            
            // Normalize configurations
            sipConfig.normalize();
            uaConfig.normalize(sipConfig);
            
            // Create components
            ConfiguredScheduler scheduler = new ConfiguredScheduler(schedulerConfig);
            SipProvider sipProvider = new SipProvider(sipConfig, scheduler);
            
            // Create and start server
            AudioQueueServer server = new AudioQueueServer(
                sipProvider,
                portConfig.createPool(),
                uaConfig,
                serviceConfig
            );
            
            LOGGER.info("AudioQueueServer started and listening on {}", sipProvider.getViaAddress());
            
            // Keep running until interrupted
            while (true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            LOGGER.error("Error starting server", e);
            System.exit(1);
        }
    }
} 