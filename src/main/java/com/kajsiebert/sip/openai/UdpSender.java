package com.kajsiebert.sip.openai;

import java.nio.ByteBuffer;
import java.util.Random;

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
        int sequence = 0;
        int timestamp = 0;
        int ssrc = new Random().nextInt();

        while (running) {
            try {
                // Take one encoded G.711 160-byte frame from the queue
                byte[] frame = audioQueues.getOutputAudioQueue().take();
        
                if (frame.length != 160) {
                    LOG.warn("Unexpected frame size: {} bytes (expected 160)", frame.length);
                    continue; // Skip invalid frame
                }
        
                byte[] rtpPacket = createRtpPacket(frame, sequence++, timestamp, ssrc);
                timestamp += 160; // 160 samples at 8000 Hz = 20 ms
        
                UdpPacket packet = new UdpPacket(rtpPacket, rtpPacket.length);
                packet.setIpAddress(socketContext.getDestAddress());
                packet.setPort(socketContext.getDestPort());
                socketContext.getSocket().send(packet);
        
                LOG.debug("Sent RTP packet: seq={}, timestamp={}, length={} to {} port {}", sequence, timestamp, rtpPacket.length, socketContext.getDestAddress(), socketContext.getDestPort());
            } catch (Exception e) {
                if (!running) break;
                LOG.error("Error sending UDP packet", e);
            }
        }
    }

    public byte[] createRtpPacket(byte[] audioFrame, int seqNum, int timestamp, int ssrc) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + audioFrame.length);
        buffer.put((byte) 0x80); // RTP version 2
        buffer.put((byte) 0x08); // Payload type 8 (PCMA)
        buffer.putShort((short) seqNum);
        buffer.putInt(timestamp);
        buffer.putInt(ssrc);
        buffer.put(audioFrame);
        return buffer.array();
    }
}
