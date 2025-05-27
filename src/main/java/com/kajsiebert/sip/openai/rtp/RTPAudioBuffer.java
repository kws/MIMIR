package com.kajsiebert.sip.openai.rtp;

import io.vertx.core.buffer.Buffer;
import java.util.PriorityQueue;

/**
 * A JitterPacket is a packet that may have been received out of order. It contains the timestamp of the packet and the payload.
 */
class JitterPacket implements Comparable<JitterPacket> {
    final long timestamp;
    byte[] payload;

    JitterPacket(long timestamp, byte[] payload) {
        this.timestamp = timestamp;
        this.payload = payload;
    }

    @Override
    public int compareTo(JitterPacket other) {
        return Long.compare(this.timestamp, other.timestamp);
    }
}

/**
 * The RTPAudioBuffer is the 'receiving' partner of the @link{RTPAudioQueue}. It handles receiving RTP packets,
 * ordering them based on timestamp to fix jitter and then storing them up until we have enough to send to the websocket.
 */
public class RTPAudioBuffer {

    private final PriorityQueue<JitterPacket> jitterBuffer = new PriorityQueue<>();

    public void appendPacket(Buffer rtpPacket) {
        if (rtpPacket.length() > RTPConstants.RTP_HEADER_SIZE) {
            long ts = rtpPacket.getInt(4) & 0xffffffffL;
            byte[] payload = rtpPacket.getBytes(RTPConstants.RTP_HEADER_SIZE, rtpPacket.length());

            jitterBuffer.offer(new JitterPacket(ts, payload));
        }
    }

    public byte[] getAudioBuffer() {
        Buffer combined = Buffer.buffer();
        while (!jitterBuffer.isEmpty()) {   
            JitterPacket pkt = jitterBuffer.poll();
            combined.appendBytes(pkt.payload);
        }
        return combined.getBytes();
    }

}
