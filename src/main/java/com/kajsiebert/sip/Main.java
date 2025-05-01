package com.kajsiebert.sip;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for the SIP client application.
 * This class provides the entry point for the application and handles
 * command-line input for controlling the client.
 */
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    
    private static SipClient sipClient;
    private static AudioProcessor audioProcessor;
    
    /**
     * Main entry point for the application.
     * 
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        LOGGER.info("Starting SIP client application");
        
        try {
            // Create the configuration
            Config config = new Config();
            
            // Default echo delay in milliseconds
            int echoDelay = 500;
            boolean echoMode = true;
            
            // Parse command-line arguments
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                
                if (arg.equals("--username") && i < args.length - 1) {
                    config.setUsername(args[++i]);
                } else if (arg.equals("--password") && i < args.length - 1) {
                    config.setPassword(args[++i]);
                } else if (arg.equals("--domain") && i < args.length - 1) {
                    config.setDomain(args[++i]);
                } else if (arg.equals("--proxy") && i < args.length - 1) {
                    String[] parts = args[++i].split(":");
                    config.setProxyAddress(parts[0]);
                    if (parts.length > 1) {
                        config.setProxyPort(Integer.parseInt(parts[1]));
                    }
                } else if (arg.equals("--registrar") && i < args.length - 1) {
                    String[] parts = args[++i].split(":");
                    config.setRegistrarAddress(parts[0]);
                    if (parts.length > 1) {
                        config.setRegistrarPort(Integer.parseInt(parts[1]));
                    }
                } else if (arg.equals("--local-port") && i < args.length - 1) {
                    config.setLocalPort(Integer.parseInt(args[++i]));
                } else if (arg.equals("--media-port") && i < args.length - 1) {
                    config.setMediaPort(Integer.parseInt(args[++i]));
                } else if (arg.equals("--auth-username") && i < args.length - 1) {
                    config.setAuthUsername(args[++i]);
                } else if (arg.equals("--local-address") && i < args.length - 1) {
                    config.setLocalAddress(args[++i]);
                } else if (arg.equals("--echo-delay") && i < args.length - 1) {
                    echoDelay = Integer.parseInt(args[++i]);
                } else if (arg.equals("--echo-mode") && i < args.length - 1) {
                    echoMode = Boolean.parseBoolean(args[++i]);
                } else if (arg.equals("--help")) {
                    printHelp();
                    return;
                }
            }
            
            // Create the audio processor
            audioProcessor = new AudioProcessor();
            
            // Set echo mode
            if (echoMode) {
                audioProcessor.setEchoMode(true, echoDelay);
                LOGGER.info("Echo mode enabled with delay: {}ms", echoDelay);
            }
            
            // Create and start the SIP client
            sipClient = new SipClient(config);
            sipClient.setAudioProcessor(audioProcessor);
            sipClient.start();
            
            // Handle command-line input
            handleUserInput();
            
        } catch (Exception e) {
            LOGGER.error("Error in main", e);
        }
    }
    
    /**
     * Handle user input from the command line.
     */
    private static void handleUserInput() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        boolean running = true;
        
        LOGGER.info("SIP client started. Type 'help' for available commands.");
        
        while (running) {
            try {
                System.out.print("> ");
                String line = reader.readLine();
                
                if (line == null || line.trim().equalsIgnoreCase("quit") || line.trim().equalsIgnoreCase("exit")) {
                    running = false;
                } else if (line.trim().equalsIgnoreCase("help")) {
                    printCommands();
                } else if (line.trim().equalsIgnoreCase("status")) {
                    printStatus();
                } else if (line.trim().equalsIgnoreCase("register")) {
                    sipClient.start();
                } else if (line.trim().equalsIgnoreCase("unregister")) {
                    sipClient.stop();
                } else if (line.trim().equalsIgnoreCase("passthrough on")) {
                    audioProcessor.setPassthrough(true);
                    LOGGER.info("Passthrough mode enabled");
                } else if (line.trim().equalsIgnoreCase("passthrough off")) {
                    audioProcessor.setPassthrough(false);
                    LOGGER.info("Passthrough mode disabled");
                } else if (line.trim().equalsIgnoreCase("echo on")) {
                    audioProcessor.setEchoMode(true, audioProcessor.getEchoDelay());
                    LOGGER.info("Echo mode enabled with delay: {}ms", audioProcessor.getEchoDelay());
                } else if (line.trim().equalsIgnoreCase("echo off")) {
                    audioProcessor.setEchoMode(false, 0);
                    LOGGER.info("Echo mode disabled");
                } else if (line.trim().startsWith("echo delay ")) {
                    try {
                        int delay = Integer.parseInt(line.trim().substring(11));
                        audioProcessor.setEchoDelay(delay);
                        LOGGER.info("Echo delay set to: {}ms", delay);
                    } catch (NumberFormatException e) {
                        LOGGER.error("Invalid delay value. Usage: echo delay <milliseconds>");
                    }
                } else if (line.trim().equalsIgnoreCase("stats")) {
                    printDetailedStats();
                } else if (!line.trim().isEmpty()) {
                    LOGGER.info("Unknown command: {}", line);
                    printCommands();
                }
            } catch (Exception e) {
                LOGGER.error("Error handling user input", e);
            }
        }
        
        // Clean up
        if (sipClient != null) {
            sipClient.stop();
        }
        
        LOGGER.info("SIP client application terminated");
    }
    
    /**
     * Print the status of the SIP client.
     */
    private static void printStatus() {
        boolean registered = sipClient != null && sipClient.isRegistered();
        boolean callActive = sipClient != null && sipClient.isCallActive();
        boolean processing = audioProcessor != null && audioProcessor.isProcessing();
        
        LOGGER.info("Status:");
        LOGGER.info("  Registered: {}", registered);
        LOGGER.info("  Call active: {}", callActive);
        LOGGER.info("  Audio processing: {}", processing);
        
        if (audioProcessor != null) {
            LOGGER.info("  Passthrough mode: {}", audioProcessor.isPassthrough());
            LOGGER.info("  Echo mode: {}", audioProcessor.isEchoMode());
            if (audioProcessor.isEchoMode()) {
                LOGGER.info("  Echo delay: {}ms", audioProcessor.getEchoDelay());
            }
        }
    }
    
    /**
     * Print detailed statistics about the SIP client and audio processing.
     */
    private static void printDetailedStats() {
        if (sipClient != null) {
            LOGGER.info(sipClient.getAudioStats());
        } else {
            LOGGER.info("SIP client not available");
        }
    }
    
    /**
     * Print the available commands.
     */
    private static void printCommands() {
        LOGGER.info("Available commands:");
        LOGGER.info("  help - Print this help message");
        LOGGER.info("  status - Print the status of the SIP client");
        LOGGER.info("  stats - Print detailed statistics");
        LOGGER.info("  register - Register with the SIP server");
        LOGGER.info("  unregister - Unregister from the SIP server");
        LOGGER.info("  passthrough on - Enable passthrough mode (no audio processing)");
        LOGGER.info("  passthrough off - Disable passthrough mode (process audio)");
        LOGGER.info("  echo on - Enable echo mode");
        LOGGER.info("  echo off - Disable echo mode");
        LOGGER.info("  echo delay <ms> - Set echo delay in milliseconds");
        LOGGER.info("  quit/exit - Exit the application");
    }
    
    /**
     * Print the help message.
     */
    private static void printHelp() {
        System.out.println("SIP Client - A headless SIP client for intercepting RTP audio streams");
        System.out.println();
        System.out.println("Usage: java -jar sip-client.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --username <username>       SIP username");
        System.out.println("  --password <password>       SIP password");
        System.out.println("  --domain <domain>           SIP domain/realm");
        System.out.println("  --proxy <host:port>         SIP proxy address and port");
        System.out.println("  --registrar <host:port>     SIP registrar address and port");
        System.out.println("  --local-port <port>         Local port for SIP (default: 5060)");
        System.out.println("  --media-port <port>         Local port for RTP media (default: 4000)");
        System.out.println("  --auth-username <username>  Authentication username (if different from SIP username)");
        System.out.println("  --local-address <address>   Local IP address to bind to");
        System.out.println("  --echo-delay <ms>           Echo delay in milliseconds (default: 500)");
        System.out.println("  --echo-mode <true|false>    Enable or disable echo mode (default: true)");
        System.out.println("  --help                      Print this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar sip-client.jar --username 1000 --password secret --domain kajsiebert.com --proxy kajsiebert.com:5060");
    }
}