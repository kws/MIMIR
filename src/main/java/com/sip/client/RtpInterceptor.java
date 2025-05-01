package com.sip.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts RTP packets, processes them, and forwards them to their destination.
 * This class acts as a proxy between the SIP/RTP stack and the remote endpoint.
 */
public class RtpInterceptor implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtpInterceptor.class);

    // Default buffer size for RTP packets (1500 bytes should be enough for most packets)
    private static final int BUFFER_SIZE = 1500;
    
    // Socket configuration
    private final int localPort;
    private final InetAddress remoteAddress;
    private final int remotePort;
    
    // Sockets for sending and receiving RTP packets
    private DatagramSocket receiveSocket;
    private DatagramSocket sendSocket;
    
    // Thread for the interceptor
    private Thread interceptorThread;
    
    // Processing components
    private AudioProcessor audioProcessor;
    
    // State flags
    private volatile boolean running;
    
    // Statistics
    private long packetsSent;
    private long packetsReceived;
    private long bytesReceived;
    private long bytesSent;

    /**
     * Create a new RTP interceptor.
     *
     * @param localPort The local port to listen on
     * @param remoteAddress The remote address to forward packets to
     * @param remotePort The remote port to forward packets to
     * @param audioProcessor The audio processor to use
     */
    public RtpInterceptor(int localPort, InetAddress remoteAddress, int remotePort, AudioProcessor audioProcessor) {
        this.localPort = localPort;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.audioProcessor = audioProcessor;
        this.packetsSent = 0;
        this.packetsReceived = 0;
        this.bytesReceived = 0;
        this.bytesSent = 0;
    }

    /**
     * Start the RTP interceptor.
     *
     * @throws IOException If the sockets cannot be opened
     */
    public void start() throws IOException {
        LOGGER.info("Starting RTP interceptor on port {} -> {}:{}", localPort, remoteAddress.getHostAddress(), remotePort);
        
        // Create the sockets
        receiveSocket = new DatagramSocket(localPort);
        sendSocket = new DatagramSocket();
        
        // Set socket options for low latency
        receiveSocket.setTrafficClass(0x10); // IPTOS_LOWDELAY
        sendSocket.setTrafficClass(0x10);    // IPTOS_LOWDELAY
        
        // Set receive buffer size
        receiveSocket.setReceiveBufferSize(65536);
        sendSocket.setSendBufferSize(65536);
        
        // Start the interceptor thread
        running = true;
        interceptorThread = new Thread(this, "RtpInterceptor-" + localPort);
        interceptorThread.start();
        
        LOGGER.info("RTP interceptor started successfully");
    }

    /**
     * Stop the RTP interceptor.
     */
    public void stop() {
        LOGGER.info("Stopping RTP interceptor");
        
        running = false;
        
        // Close the sockets
        if (receiveSocket != null) {
            receiveSocket.close();
            receiveSocket = null;
        }
        
        if (sendSocket != null) {
            sendSocket.close();
            sendSocket = null;
        }
        
        // Wait for the thread to finish
        if (interceptorThread != null) {
            try {
                interceptorThread.join(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for interceptor thread to finish", e);
            }
            interceptorThread = null;
        }
        
        LOGGER.info("RTP interceptor stopped. Stats: Received {} packets ({} bytes), Sent {} packets ({} bytes)",
                packetsReceived, bytesReceived, packetsSent, bytesSent);
    }

    @Override
    public void run() {
        LOGGER.info("RTP interceptor thread started");
        
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        while (running) {
            try {
                // Receive a packet
                receiveSocket.receive(packet);
                packetsReceived++;
                bytesReceived += packet.getLength();
                
                // Extract the audio data
                byte[] audioData = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), audioData, 0, packet.getLength());
                
                // Process the audio data
                byte[] processedData = audioProcessor.processAudio(audioData);
                
                // Forward the processed packet
                DatagramPacket outPacket = new DatagramPacket(
                        processedData, processedData.length, remoteAddress, remotePort);
                sendSocket.send(outPacket);
                packetsSent++;
                bytesSent += processedData.length;
                
            } catch (IOException e) {
                if (running) {
                    LOGGER.error("Error in RTP interceptor", e);
                }
            }
        }
        
        LOGGER.info("RTP interceptor thread finished");
    }
    
    /**
     * Get the local port used by this interceptor.
     *
     * @return The local port
     */
    public int getLocalPort() {
        return localPort;
    }
    
    /**
     * Get the remote address used by this interceptor.
     *
     * @return The remote address
     */
    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }
    
    /**
     * Get the remote port used by this interceptor.
     *
     * @return The remote port
     */
    public int getRemotePort() {
        return remotePort;
    }
    
    /**
     * Check if the interceptor is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Get the number of packets received.
     *
     * @return The number of packets received
     */
    public long getPacketsReceived() {
        return packetsReceived;
    }
    
    /**
     * Get the number of packets sent.
     *
     * @return The number of packets sent
     */
    public long getPacketsSent() {
        return packetsSent;
    }
    
    /**
     * Get the number of bytes received.
     *
     * @return The number of bytes received
     */
    public long getBytesReceived() {
        return bytesReceived;
    }
    
    /**
     * Get the number of bytes sent.
     *
     * @return The number of bytes sent
     */
    public long getBytesSent() {
        return bytesSent;
    }
}
