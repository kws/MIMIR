package com.kajsiebert.sip.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.net.UdpPacket;

public class UdpSender extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(UdpSender.class);
    private final UdpSocketContext socketContext;
    private final AudioQueues audioQueues;
    private boolean running = true;

    public UdpSender(UdpSocketContext socketContext, AudioQueues audioQueues) {
        this.socketContext = socketContext;
        this.audioQueues = audioQueues;
    }

    public void stopSender() {
        running = false;
        this.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Take data from the output queue
                byte[] data = audioQueues.getOutputAudioQueue().take();
                
                // Create and send the UDP packet
                UdpPacket packet = new UdpPacket(data, data.length);
                packet.setIpAddress(socketContext.getDestAddress());
                packet.setPort(socketContext.getDestPort());
                socketContext.getSocket().send(packet);
            } catch (Exception e) {
                if (!running) break;
                LOG.error("Error sending UDP packet", e);
            }
        }
    }
}
