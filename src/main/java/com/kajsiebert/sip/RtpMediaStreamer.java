package com.kajsiebert.sip;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Media streamer that intercepts RTP packets for audio processing.
 * This class implements the MediaStreamer interface from mjSIP to
 * intercept and process RTP audio packets.
 */
public class RtpMediaStreamer implements MediaStreamer, Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtpMediaStreamer.class);
    
    // Default buffer size for RTP packets
    private static final int BUFFER_SIZE = 2048;
    
    // The flow specification
    private final FlowSpec flowSpec;
    
    // Audio processor for handling RTP data
    private AudioProcessor audioProcessor;
    
    // Socket for sending and receiving
    private DatagramSocket socket;
    
    // Thread for running the packet handling
    private Thread processingThread;
    
    // Executor for background tasks
    private final Executor executor;
    
    // Control flags
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Statistics 
    private long packetsReceived = 0;
    private long packetsSent = 0;
    private long bytesReceived = 0;
    private long bytesSent = 0;
    
    /**
     * Create a new RTP media streamer.
     * 
     * @param executor The executor for running tasks
     * @param flowSpec The flow specification
     * @param audioProcessor The audio processor to use
     */
    public RtpMediaStreamer(Executor executor, FlowSpec flowSpec, AudioProcessor audioProcessor) {
        this.executor = executor;
        this.flowSpec = flowSpec;
        this.audioProcessor = audioProcessor;
    }
    
    /**
     * Set the audio processor for this streamer.
     * 
     * @param audioProcessor The audio processor to use
     */
    public void setAudioProcessor(AudioProcessor audioProcessor) {
        this.audioProcessor = audioProcessor;
    }
    
    @Override
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("RTP streamer already started");
            return false;
        }
        
        try {
            // Create a socket for sending and receiving RTP packets
            socket = new DatagramSocket(flowSpec.getLocalPort());
            
            // Configure socket options
            socket.setTrafficClass(0x10); // IPTOS_LOWDELAY
            socket.setSoTimeout(0); // No timeout
            socket.setReceiveBufferSize(65536);
            socket.setSendBufferSize(65536);
            
            // Start the processing thread
            processingThread = new Thread(this, "RtpMediaStreamer-" + flowSpec.getLocalPort());
            processingThread.start();
            
            LOGGER.info("RTP media streamer started: local port {}, remote {}:{}",
                    flowSpec.getLocalPort(), flowSpec.getRemoteAddr(), flowSpec.getRemotePort());
                    
            return true;
        } catch (SocketException e) {
            LOGGER.error("Failed to create RTP socket on port {}", flowSpec.getLocalPort(), e);
            running.set(false);
            return false;
        }
    }
    
    @Override
    public void halt() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        
        // Close the socket
        if (socket != null) {
            socket.close();
            socket = null;
        }
        
        // Wait for the thread to finish
        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for processing thread to finish");
            }
            processingThread = null;
        }
        
        LOGGER.info("RTP media streamer stopped: {} received packets, {} sent packets",
                packetsReceived, packetsSent);
    }
    
    @Override
    public void run() {
        LOGGER.info("RTP processing thread started");
        
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // Receive a packet
                    socket.receive(packet);
                    packetsReceived++;
                    bytesReceived += packet.getLength();
                    
                    // Extract the audio data
                    byte[] audioData = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), audioData, 0, packet.getLength());
                    
                    // Process the audio data
                    byte[] processedData = audioProcessor.processAudio(audioData);
                    
                    // Forward the processed packet
                    InetAddress remoteAddr = InetAddress.getByName(flowSpec.getRemoteAddr());
                    DatagramPacket outPacket = new DatagramPacket(
                            processedData, processedData.length, remoteAddr, flowSpec.getRemotePort());
                    socket.send(outPacket);
                    packetsSent++;
                    bytesSent += processedData.length;
                    
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.error("Error processing RTP packet", e);
                    }
                }
            }
        } finally {
            LOGGER.info("RTP processing thread finished");
        }
    }
    
    /**
     * Get the local port for this streamer.
     * 
     * @return The local port
     */
    public int getLocalPort() {
        return flowSpec.getLocalPort();
    }
    
    /**
     * Get the flow specification for this streamer.
     * 
     * @return The flow specification
     */
    public FlowSpec getFlowSpec() {
        return flowSpec;
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
    
    /**
     * Check if the streamer is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }
}