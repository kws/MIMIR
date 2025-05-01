package com.kajsiebert.sip.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.net.UdpPacket;

public class UdpReceiver extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(UdpReceiver.class);
    private final UdpSocketContext socketContext;
    private final AudioQueues audioQueues;
    private boolean running = true;

    public UdpReceiver(UdpSocketContext socketContext, AudioQueues audioQueues) {
        this.socketContext = socketContext;
        this.audioQueues = audioQueues;
    }

    public void stopReceiver() {
        running = false;
        this.interrupt();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[socketContext.getMaxPacketSize()];
        UdpPacket packet = new UdpPacket(buffer, buffer.length);

        while (running) {
            try {
                socketContext.getSocket().receive(packet);
                
                // Copy the received data into a new byte array
                byte[] receivedData = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, receivedData, 0, packet.getLength());
                
                // Put the data into the input queue
                audioQueues.putRTPFrame(receivedData);
                
                // Reset the packet for next receive
                packet = new UdpPacket(buffer, buffer.length);
            } catch (Exception e) {
                if (!running) break;
                LOG.error("Error receiving UDP packet", e);
            }
        }
    }
}
