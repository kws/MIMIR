package com.kajsiebert.mimir.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

@DisplayName("RTPAudioQueue Tests")
class RTPAudioQueueTest {

  private RTPAudioQueue audioQueue;

  @BeforeEach
  void setUp() {
    audioQueue = new RTPAudioQueue();
  }

  @Test
  @DisplayName("Should return null when no audio data available")
  void shouldReturnNullWhenNoAudioDataAvailable() {
    Buffer result = audioQueue.getNextRtpPacket();

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Should create RTP packet from small audio data")
  void shouldCreateRtpPacketFromSmallAudioData() {
    byte[] audioData = {1, 2, 3, 4, 5};
    audioQueue.appendAudio(audioData);

    Buffer rtpPacket = audioQueue.getNextRtpPacket();

    assertThat(rtpPacket).isNotNull();
    assertThat(rtpPacket.length()).isEqualTo(RTPConstants.RTP_HEADER_SIZE + audioData.length);

    // Verify payload matches original audio data
    byte[] payload = rtpPacket.getBytes(RTPConstants.RTP_HEADER_SIZE, rtpPacket.length());
    assertThat(payload).isEqualTo(audioData);
  }

  @Test
  @DisplayName("Should create multiple packets from large audio data")
  void shouldCreateMultiplePacketsFromLargeAudioData() {
    // Create audio data larger than RTP_PACKET_SIZE
    byte[] largeAudioData = new byte[RTPConstants.RTP_PACKET_SIZE * 2 + 10];
    for (int i = 0; i < largeAudioData.length; i++) {
      largeAudioData[i] = (byte) (i % 256);
    }

    audioQueue.appendAudio(largeAudioData);

    // First packet should contain RTP_PACKET_SIZE bytes
    Buffer packet1 = audioQueue.getNextRtpPacket();
    assertThat(packet1).isNotNull();
    assertThat(packet1.length())
        .isEqualTo(RTPConstants.RTP_HEADER_SIZE + RTPConstants.RTP_PACKET_SIZE);

    // Second packet should contain RTP_PACKET_SIZE bytes
    Buffer packet2 = audioQueue.getNextRtpPacket();
    assertThat(packet2).isNotNull();
    assertThat(packet2.length())
        .isEqualTo(RTPConstants.RTP_HEADER_SIZE + RTPConstants.RTP_PACKET_SIZE);

    // Third packet should contain remaining 10 bytes
    Buffer packet3 = audioQueue.getNextRtpPacket();
    assertThat(packet3).isNotNull();
    assertThat(packet3.length()).isEqualTo(RTPConstants.RTP_HEADER_SIZE + 10);

    // No more packets should be available
    Buffer packet4 = audioQueue.getNextRtpPacket();
    assertThat(packet4).isNull();
  }

  @Test
  @DisplayName("Should increment sequence number for each packet")
  void shouldIncrementSequenceNumberForEachPacket() {
    // Add enough audio for multiple packets
    byte[] audioData = new byte[RTPConstants.RTP_PACKET_SIZE];
    audioQueue.appendAudio(audioData);
    audioQueue.appendAudio(audioData);

    Buffer packet1 = audioQueue.getNextRtpPacket();
    Buffer packet2 = audioQueue.getNextRtpPacket();

    assertThat(packet1).isNotNull();
    assertThat(packet2).isNotNull();

    // Extract sequence numbers (bytes 2-3 of RTP header)
    short seq1 = packet1.getShort(2);
    short seq2 = packet2.getShort(2);

    assertThat(seq2).isEqualTo((short) (seq1 + 1));
  }

  @Test
  @DisplayName("Should update timestamp based on payload length")
  void shouldUpdateTimestampBasedOnPayloadLength() {
    byte[] audioData1 = new byte[100];
    byte[] audioData2 = new byte[200];

    audioQueue.appendAudio(audioData1);
    Buffer packet1 = audioQueue.getNextRtpPacket();

    audioQueue.appendAudio(audioData2);
    Buffer packet2 = audioQueue.getNextRtpPacket();

    assertThat(packet1).isNotNull();
    assertThat(packet2).isNotNull();

    // Extract timestamps (bytes 4-7 of RTP header)
    int timestamp1 = packet1.getInt(4);
    int timestamp2 = packet2.getInt(4);

    // Timestamp should increase by the length of the first payload
    assertThat(timestamp2).isEqualTo(timestamp1 + audioData1.length);
  }

  @Test
  @DisplayName("Should handle sequence number wraparound")
  void shouldHandleSequenceNumberWraparound() {
    // This test would require creating 65536 packets to test wraparound
    // For efficiency, we'll test the wraparound logic conceptually
    byte[] audioData = new byte[10];

    // Generate many packets to approach wraparound
    for (int i = 0; i < 65540; i++) {
      audioQueue.appendAudio(audioData);
      Buffer packet = audioQueue.getNextRtpPacket();
      assertThat(packet).isNotNull();

      short sequenceNumber = packet.getShort(2);
      // Sequence number should wrap around at 16-bit boundary
      assertThat(sequenceNumber).isEqualTo((short) (i & 0xFFFF));
    }
  }

  @Test
  @DisplayName("Should create valid RTP header")
  void shouldCreateValidRtpHeader() {
    byte[] audioData = {1, 2, 3, 4};
    audioQueue.appendAudio(audioData);

    Buffer packet = audioQueue.getNextRtpPacket();
    assertThat(packet).isNotNull();

    // Verify RTP header fields
    byte version = (byte) ((packet.getByte(0) >> 6) & 0x3);
    assertThat(version).isEqualTo((byte) 2); // RTP version 2

    boolean padding = (packet.getByte(0) & 0x20) != 0;
    assertThat(padding).isFalse(); // No padding

    boolean extension = (packet.getByte(0) & 0x10) != 0;
    assertThat(extension).isFalse(); // No extension

    byte csrcCount = (byte) (packet.getByte(0) & 0x0F);
    assertThat(csrcCount).isEqualTo((byte) 0); // No CSRC

    byte payloadType = (byte) (packet.getByte(1) & 0x7F);
    assertThat(payloadType).isEqualTo((byte) 0); // PCMU payload type

    int ssrc = packet.getInt(8);
    assertThat(ssrc).isEqualTo(RTPConstants.SSRC);
  }

  @Test
  @DisplayName("Should clear audio buffer correctly")
  void shouldClearAudioBufferCorrectly() {
    byte[] audioData = {1, 2, 3, 4, 5};
    audioQueue.appendAudio(audioData);

    // Verify data is present
    Buffer packet1 = audioQueue.getNextRtpPacket();
    assertThat(packet1).isNotNull();

    // Clear the buffer
    audioQueue.clearAudio();

    // Should not have any more data
    Buffer packet2 = audioQueue.getNextRtpPacket();
    assertThat(packet2).isNull();
  }

  @Test
  @DisplayName("Should handle multiple audio appends before packet creation")
  void shouldHandleMultipleAudioAppendsBeforePacketCreation() {
    byte[] audio1 = {1, 2, 3};
    byte[] audio2 = {4, 5, 6};
    byte[] audio3 = {7, 8, 9};

    audioQueue.appendAudio(audio1);
    audioQueue.appendAudio(audio2);
    audioQueue.appendAudio(audio3);

    Buffer packet = audioQueue.getNextRtpPacket();
    assertThat(packet).isNotNull();

    // Payload should contain all appended audio data
    byte[] payload = packet.getBytes(RTPConstants.RTP_HEADER_SIZE, packet.length());
    assertThat(payload).isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
  }

  @Test
  @DisplayName("Should handle empty audio data")
  void shouldHandleEmptyAudioData() {
    byte[] emptyAudio = new byte[0];
    audioQueue.appendAudio(emptyAudio);

    Buffer packet = audioQueue.getNextRtpPacket();
    assertThat(packet).isNull(); // No data to create packet from
  }

  @Test
  @DisplayName("Should handle audio data exactly equal to RTP packet size")
  void shouldHandleAudioDataExactlyEqualToRtpPacketSize() {
    byte[] audioData = new byte[RTPConstants.RTP_PACKET_SIZE];
    for (int i = 0; i < audioData.length; i++) {
      audioData[i] = (byte) (i % 256);
    }

    audioQueue.appendAudio(audioData);

    Buffer packet = audioQueue.getNextRtpPacket();
    assertThat(packet).isNotNull();
    assertThat(packet.length())
        .isEqualTo(RTPConstants.RTP_HEADER_SIZE + RTPConstants.RTP_PACKET_SIZE);

    // Should not have any more data
    Buffer nextPacket = audioQueue.getNextRtpPacket();
    assertThat(nextPacket).isNull();
  }

  @Test
  @DisplayName("Should maintain separate state for sequence and timestamp")
  void shouldMaintainSeparateStateForSequenceAndTimestamp() {
    byte[] audio1 = new byte[50];
    byte[] audio2 = new byte[75];

    audioQueue.appendAudio(audio1);
    Buffer packet1 = audioQueue.getNextRtpPacket();

    audioQueue.appendAudio(audio2);
    Buffer packet2 = audioQueue.getNextRtpPacket();

    assertThat(packet1).isNotNull();
    assertThat(packet2).isNotNull();

    short seq1 = packet1.getShort(2);
    short seq2 = packet2.getShort(2);
    int timestamp1 = packet1.getInt(4);
    int timestamp2 = packet2.getInt(4);

    // Sequence should increment by 1
    assertThat(seq2).isEqualTo((short) (seq1 + 1));

    // Timestamp should increment by payload length of first packet
    assertThat(timestamp2).isEqualTo(timestamp1 + audio1.length);
  }

  @Test
  @DisplayName("Should handle audio append after partial consumption")
  void shouldHandleAudioAppendAfterPartialConsumption() {
    // Add large audio data
    byte[] largeAudio = new byte[RTPConstants.RTP_PACKET_SIZE + 50];
    for (int i = 0; i < largeAudio.length; i++) {
      largeAudio[i] = (byte) (i % 256);
    }
    audioQueue.appendAudio(largeAudio);

    // Get first packet (should consume RTP_PACKET_SIZE bytes)
    Buffer packet1 = audioQueue.getNextRtpPacket();
    assertThat(packet1).isNotNull();

    // Add more audio data
    byte[] moreAudio = {100, 101, 102};
    audioQueue.appendAudio(moreAudio);

    // Get second packet (should contain remaining 50 bytes + new 3 bytes)
    Buffer packet2 = audioQueue.getNextRtpPacket();
    assertThat(packet2).isNotNull();
    assertThat(packet2.length()).isEqualTo(RTPConstants.RTP_HEADER_SIZE + 53);

    // Verify the payload contains the expected data
    byte[] payload = packet2.getBytes(RTPConstants.RTP_HEADER_SIZE, packet2.length());
    assertThat(payload).hasSize(53);

    // First 50 bytes should be from the remaining large audio
    for (int i = 0; i < 50; i++) {
      assertThat(payload[i]).isEqualTo((byte) ((RTPConstants.RTP_PACKET_SIZE + i) % 256));
    }

    // Last 3 bytes should be from the new audio
    assertThat(payload[50]).isEqualTo((byte) 100);
    assertThat(payload[51]).isEqualTo((byte) 101);
    assertThat(payload[52]).isEqualTo((byte) 102);
  }
}
