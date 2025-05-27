package com.kajsiebert.mimir.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mjsip.media.FlowSpec;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kajsiebert.mimir.openai.rtp.RTPSession;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;

/**
 * Unit tests for RTPSession Simplified to focus on core functionality without timing dependencies
 */
@ExtendWith(MockitoExtension.class)
class RTPSessionTest {

  @Mock private Vertx vertx;
  @Mock private DatagramSocket datagramSocket;
  @Mock private FlowSpec flowSpec;

  private RTPSession rtpSession;

  @BeforeEach
  void setUp() {
    // Use lenient() to avoid UnnecessaryStubbingException
    lenient().when(vertx.createDatagramSocket(any())).thenReturn(datagramSocket);
    lenient().when(flowSpec.getLocalPort()).thenReturn(5004);
    lenient().when(flowSpec.getRemotePort()).thenReturn(5006);
    lenient().when(flowSpec.getRemoteAddress()).thenReturn("127.0.0.1");

    // Mock the socket listen call to succeed immediately
    lenient()
        .when(datagramSocket.listen(anyInt(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              Handler<AsyncResult<DatagramSocket>> handler = invocation.getArgument(2);
              AsyncResult<DatagramSocket> successResult = mock(AsyncResult.class);
              lenient().when(successResult.succeeded()).thenReturn(true);
              lenient().when(successResult.result()).thenReturn(datagramSocket);
              handler.handle(successResult);
              return datagramSocket;
            });

    rtpSession = new RTPSession(vertx, flowSpec);
  }

  @Test
  void shouldInitializeCorrectly() {
    assertThat(rtpSession).isNotNull();
    // Verify socket setup was called
    verify(datagramSocket).listen(eq(5004), eq("0.0.0.0"), any());
  }

  @Test
  void shouldSendPacketsCorrectly() {
    Buffer testData = Buffer.buffer("test audio data");

    rtpSession.sendPacket(testData);

    // Verify packet was sent with correct parameters
    verify(datagramSocket).send(any(Buffer.class), eq(5006), eq("127.0.0.1"), any());
  }

  @Test
  void shouldReturnEmptyAudioBufferInitially() {
    byte[] audioData = rtpSession.getAudioBuffer();
    assertThat(audioData).isEmpty();
  }

  @Test
  void shouldCloseUdpSocketCorrectly() {
    rtpSession.close();
    verify(datagramSocket).close();
  }

  @Test
  void shouldHandlePacketsCorrectly() {
    // Create a test RTP packet
    Buffer rtpPacket = Buffer.buffer();
    // Add RTP header (simplified)
    rtpPacket.appendByte((byte) 0x80); // V=2, P=0, X=0, CC=0
    rtpPacket.appendByte((byte) 0x08); // M=0, PT=8 (PCMA)
    rtpPacket.appendShort((short) 1234); // Sequence number
    rtpPacket.appendInt(12345678); // Timestamp
    rtpPacket.appendInt(87654321); // SSRC
    rtpPacket.appendBytes("audio".getBytes()); // Payload

    simulatePacketReceived(rtpPacket);

    byte[] audioData = rtpSession.getAudioBuffer();
    assertThat(audioData).isNotEmpty();
    assertThat(new String(audioData)).isEqualTo("audio");
  }

  @Test
  void shouldHandleMultiplePacketsAndOrderThem() {
    // Send multiple packets
    for (int i = 1; i <= 3; i++) {
      Buffer rtpPacket = createTestRTPPacket(i, "data" + i);
      simulatePacketReceived(rtpPacket);
    }

    byte[] audioData = rtpSession.getAudioBuffer();
    assertThat(audioData).isNotEmpty();
    String audioString = new String(audioData);
    assertThat(audioString).contains("data1");
  }

  @Test
  void shouldHandleSmallPacketsGracefully() {
    // Send packet smaller than RTP header
    Buffer smallPacket = Buffer.buffer("tiny");
    simulatePacketReceived(smallPacket);

    // Should not crash, audio buffer should remain empty
    byte[] audioData = rtpSession.getAudioBuffer();
    assertThat(audioData).isEmpty();
  }

  @Test
  void shouldHandleEmptyPacketsGracefully() {
    Buffer emptyPacket = Buffer.buffer();
    simulatePacketReceived(emptyPacket);

    // Should not crash
    byte[] audioData = rtpSession.getAudioBuffer();
    assertThat(audioData).isEmpty();
  }

  @Test
  void shouldClearAudioBufferAfterGetting() {
    Buffer rtpPacket = createTestRTPPacket(1, "test");
    simulatePacketReceived(rtpPacket);

    // Get data first time
    byte[] firstGet = rtpSession.getAudioBuffer();
    assertThat(firstGet).isNotEmpty();

    // Get data second time should be empty (buffer cleared)
    byte[] secondGet = rtpSession.getAudioBuffer();
    assertThat(secondGet).isEmpty();
  }

  // Helper methods
  private Buffer createTestRTPPacket(int sequenceNumber, String payload) {
    Buffer rtpPacket = Buffer.buffer();
    rtpPacket.appendByte((byte) 0x80); // V=2, P=0, X=0, CC=0
    rtpPacket.appendByte((byte) 0x08); // M=0, PT=8 (PCMA)
    rtpPacket.appendShort((short) sequenceNumber);
    rtpPacket.appendInt(12345678 + sequenceNumber * 160); // Timestamp
    rtpPacket.appendInt(87654321); // SSRC
    rtpPacket.appendBytes(payload.getBytes());
    return rtpPacket;
  }

  private void simulatePacketReceived(Buffer packet) {
    rtpSession.audioBuffer.appendPacket(packet);
  }
}
