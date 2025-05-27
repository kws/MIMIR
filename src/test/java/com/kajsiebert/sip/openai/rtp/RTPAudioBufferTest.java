package com.kajsiebert.sip.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

@DisplayName("RTPAudioBuffer Tests")
class RTPAudioBufferTest {

  private RTPAudioBuffer audioBuffer;

  @BeforeEach
  void setUp() {
    audioBuffer = new RTPAudioBuffer();
  }

  @Test
  @DisplayName("Should handle empty buffer correctly")
  void shouldHandleEmptyBufferCorrectly() {
    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isNotNull();
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should append single RTP packet correctly")
  void shouldAppendSingleRtpPacketCorrectly() {
    Buffer rtpPacket = createRtpPacket(1000L, new byte[] {1, 2, 3, 4});

    audioBuffer.appendPacket(rtpPacket);
    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isEqualTo(new byte[] {1, 2, 3, 4});
  }

  @Test
  @DisplayName("Should order packets by timestamp correctly")
  void shouldOrderPacketsByTimestampCorrectly() {
    // Add packets out of order
    Buffer packet3 = createRtpPacket(3000L, new byte[] {3, 3, 3});
    Buffer packet1 = createRtpPacket(1000L, new byte[] {1, 1, 1});
    Buffer packet2 = createRtpPacket(2000L, new byte[] {2, 2, 2});

    audioBuffer.appendPacket(packet3);
    audioBuffer.appendPacket(packet1);
    audioBuffer.appendPacket(packet2);

    byte[] result = audioBuffer.getAudioBuffer();

    // Should be ordered by timestamp: packet1, packet2, packet3
    assertThat(result).isEqualTo(new byte[] {1, 1, 1, 2, 2, 2, 3, 3, 3});
  }

  @Test
  @DisplayName("Should handle packets with same timestamp")
  void shouldHandlePacketsWithSameTimestamp() {
    Buffer packet1 = createRtpPacket(1000L, new byte[] {1, 1});
    Buffer packet2 = createRtpPacket(1000L, new byte[] {2, 2});

    audioBuffer.appendPacket(packet1);
    audioBuffer.appendPacket(packet2);

    byte[] result = audioBuffer.getAudioBuffer();

    // Should contain both packets' data
    assertThat(result).hasSize(4);
    assertThat(result).containsAnyOf((byte) 1, (byte) 2);
  }

  @Test
  @DisplayName("Should ignore packets smaller than RTP header size")
  void shouldIgnorePacketsSmallerThanRtpHeaderSize() {
    // Create a packet smaller than RTP_HEADER_SIZE (12 bytes)
    Buffer smallPacket = Buffer.buffer();
    smallPacket.appendBytes(new byte[] {1, 2, 3, 4, 5}); // Only 5 bytes

    audioBuffer.appendPacket(smallPacket);
    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should handle packet with exactly header size")
  void shouldHandlePacketWithExactlyHeaderSize() {
    // Create packet with exactly RTP_HEADER_SIZE bytes (no payload)
    Buffer headerOnlyPacket = Buffer.buffer();
    headerOnlyPacket.appendBytes(new byte[12]); // Exactly RTP_HEADER_SIZE
    headerOnlyPacket.setInt(4, 1000); // Set timestamp

    audioBuffer.appendPacket(headerOnlyPacket);
    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isEmpty(); // No payload data
  }

  @Test
  @DisplayName("Should handle large number of packets")
  void shouldHandleLargeNumberOfPackets() {
    int packetCount = 100;

    // Add many packets with different timestamps
    for (int i = 0; i < packetCount; i++) {
      long timestamp = i * 1000L;
      byte[] payload = new byte[] {(byte) i};
      Buffer packet = createRtpPacket(timestamp, payload);
      audioBuffer.appendPacket(packet);
    }

    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).hasSize(packetCount);
    // Verify ordering - should be 0, 1, 2, ..., 99
    for (int i = 0; i < packetCount; i++) {
      assertThat(result[i]).isEqualTo((byte) i);
    }
  }

  @Test
  @DisplayName("Should handle packets with wraparound timestamps")
  void shouldHandlePacketsWithWraparoundTimestamps() {
    // Test with timestamps that might wraparound (large values)
    Buffer packet1 = createRtpPacket(0xFFFFFFFFL, new byte[] {1}); // Max unsigned int
    Buffer packet2 = createRtpPacket(0x00000001L, new byte[] {2}); // Small value
    Buffer packet3 = createRtpPacket(0x80000000L, new byte[] {3}); // Middle value

    audioBuffer.appendPacket(packet1);
    audioBuffer.appendPacket(packet2);
    audioBuffer.appendPacket(packet3);

    byte[] result = audioBuffer.getAudioBuffer();

    // Should handle the ordering based on the natural Long.compare behavior
    assertThat(result).hasSize(3);
  }

  @Test
  @DisplayName("Should clear buffer after getting audio data")
  void shouldClearBufferAfterGettingAudioData() {
    Buffer packet = createRtpPacket(1000L, new byte[] {1, 2, 3});
    audioBuffer.appendPacket(packet);

    // First call should return data
    byte[] result1 = audioBuffer.getAudioBuffer();
    assertThat(result1).isEqualTo(new byte[] {1, 2, 3});

    // Second call should return empty buffer
    byte[] result2 = audioBuffer.getAudioBuffer();
    assertThat(result2).isEmpty();
  }

  @Test
  @DisplayName("Should handle mixed payload sizes")
  void shouldHandleMixedPayloadSizes() {
    Buffer packet1 = createRtpPacket(1000L, new byte[] {1});
    Buffer packet2 = createRtpPacket(2000L, new byte[] {2, 2, 2, 2, 2});
    Buffer packet3 = createRtpPacket(3000L, new byte[] {3, 3});

    audioBuffer.appendPacket(packet1);
    audioBuffer.appendPacket(packet2);
    audioBuffer.appendPacket(packet3);

    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isEqualTo(new byte[] {1, 2, 2, 2, 2, 2, 3, 3});
  }

  @Test
  @DisplayName("Should handle zero-length payload")
  void shouldHandleZeroLengthPayload() {
    Buffer packet = createRtpPacket(1000L, new byte[0]);

    audioBuffer.appendPacket(packet);
    byte[] result = audioBuffer.getAudioBuffer();

    assertThat(result).isEmpty();
  }

  /**
   * Helper method to create an RTP packet with the specified timestamp and payload. This creates a
   * minimal valid RTP packet for testing purposes.
   */
  private Buffer createRtpPacket(long timestamp, byte[] payload) {
    Buffer packet = Buffer.buffer();

    // RTP Header (12 bytes minimum)
    packet.appendByte((byte) 0x80); // Version 2, no padding, no extension, no CSRC
    packet.appendByte((byte) 0x00); // Payload type
    packet.appendShort((short) 1); // Sequence number
    packet.appendInt((int) timestamp); // Timestamp (position 4-7)
    packet.appendInt(0x12345678); // SSRC

    // Payload
    packet.appendBytes(payload);

    return packet;
  }
}
