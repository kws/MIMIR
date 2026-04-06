package com.kajsiebert.mimir.openai.rtp;

import io.vertx.core.buffer.Buffer;

/**
 * In our Websocket <-> RTP pipeline, we need to split our websocket originated TCP packets into RTP
 * packets, and send these at a fixed rate of 160 bytes every 20ms.
 *
 * <p>We handle that by appending audio to an audio buffer, and then creating RTP packets from the
 * audio buffer.
 */
public class RTPAudioQueue {
  private static final int AUDIO_BUFFER_SIZE = 32 * 1024;

  private Buffer audioBuffer = Buffer.buffer(AUDIO_BUFFER_SIZE);
  private int sequenceNumber = 0;
  private long timestamp = 0;

  public void appendAudio(byte[] audio) {
    audioBuffer.appendBytes(audio);
  }

  public void clearAudio() {
    audioBuffer = Buffer.buffer(AUDIO_BUFFER_SIZE);
  }

  public Buffer getNextRtpPacket() {
    // Check if we have any audio data
    if (audioBuffer.length() == 0) {
      return null; // No data available
    }

    // Pop the next available bytes up to a max of RTP_PACKET_SIZE
    int bytesToTake = Math.min(audioBuffer.length(), RTPConstants.RTP_PACKET_SIZE);
    byte[] audioPayload = audioBuffer.getBytes(0, bytesToTake);

    // Create a new buffer with the remaining data
    Buffer remaining = Buffer.buffer();
    if (audioBuffer.length() > bytesToTake) {
      remaining.appendBytes(audioBuffer.getBytes(bytesToTake, audioBuffer.length()));
    }
    audioBuffer = remaining;

    // Create and return the RTP packet
    return createRtpPacket(audioPayload);
  }

  private Buffer createRtpPacket(byte[] payload) {
    Buffer packet = Buffer.buffer(RTPConstants.RTP_HEADER_SIZE + payload.length);

    // RTP Header (12 bytes)
    // Version 2, no padding, no extension, no CSRC
    packet.appendByte((byte) 0x80);
    // Payload type 0 for PCMU
    packet.appendByte((byte) 0x00);
    // Sequence number (16 bits)
    packet.appendShort((short) sequenceNumber);
    // Timestamp (32 bits)
    packet.appendInt((int) timestamp);
    // SSRC (32 bits)
    packet.appendInt(RTPConstants.SSRC);

    // Payload
    packet.appendBytes(payload);

    // Update sequence number and timestamp
    sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
    timestamp += payload.length;

    return packet;
  }
}
