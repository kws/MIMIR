package com.kajsiebert.sip.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mjsip.media.FlowSpec;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdaptiveRTPSession Tests")
class AdaptiveRTPSessionTest {

  @Mock private Vertx vertx;
  @Mock private DatagramSocket datagramSocket;
  @Mock private FlowSpec flowSpec;

  private AdaptiveRTPSession adaptiveSession;

  @BeforeEach
  void setUp() {
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

    adaptiveSession = new AdaptiveRTPSession(vertx, flowSpec);
  }

  @Test
  @DisplayName("Should initialize with adaptive mode disabled")
  void shouldInitializeWithAdaptiveModeDisabled() {
    assertThat(adaptiveSession.isAdaptiveModeEnabled()).isFalse();
    assertThat(adaptiveSession.getTotalSentPackets()).isZero();

    AdaptiveRTPSession.TimingMetrics metrics = adaptiveSession.getTimingMetrics();
    assertThat(metrics.sampleCount).isZero();
    assertThat(metrics.averageInterval).isZero();
    assertThat(metrics.averageJitter).isZero();
    assertThat(metrics.maxJitter).isZero();
  }

  @Test
  @DisplayName("Should send packets directly when adaptive mode is disabled")
  void shouldSendPacketsDirectlyWhenAdaptiveModeDisabled() {
    Buffer testData = Buffer.buffer("test data");

    adaptiveSession.sendPacket(testData);

    assertThat(adaptiveSession.getTotalSentPackets()).isEqualTo(1);
    assertThat(adaptiveSession.isAdaptiveModeEnabled()).isFalse();
    verify(datagramSocket).send(any(Buffer.class), anyInt(), anyString(), any());
  }

  @Test
  @DisplayName("Should track timing metrics for packet intervals")
  void shouldTrackTimingMetricsForPacketIntervals() throws InterruptedException {
    Buffer testData = Buffer.buffer("test data");

    // Send first packet
    adaptiveSession.sendPacket(testData);

    // Wait a bit to create measurable interval
    Thread.sleep(50);

    // Send second packet
    adaptiveSession.sendPacket(testData);

    AdaptiveRTPSession.TimingMetrics metrics = adaptiveSession.getTimingMetrics();
    assertThat(metrics.sampleCount).isEqualTo(1);
    assertThat(metrics.averageInterval).isGreaterThan(40.0);
    assertThat(metrics.averageJitter).isGreaterThanOrEqualTo(0.0);
    assertThat(adaptiveSession.getTotalSentPackets()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should enable adaptive mode when jitter is consistently high")
  void shouldEnableAdaptiveModeWhenJitterIsConsistentlyHigh() throws InterruptedException {
    Buffer testData = Buffer.buffer("test data");

    // Send first packet to establish baseline
    adaptiveSession.sendPacket(testData);

    // Send multiple packets with deliberately bad timing to trigger adaptive mode
    for (int i = 0; i < 15; i++) {
      Thread.sleep(10); // Create inconsistent timing
      adaptiveSession.sendPacket(testData);
    }

    // After enough samples with high jitter, adaptive mode should be enabled
    assertThat(adaptiveSession.isAdaptiveModeEnabled()).isTrue();
  }

  @Test
  @DisplayName("Should update timing metrics correctly")
  void shouldUpdateTimingMetricsCorrectly() {
    AdaptiveRTPSession.TimingMetrics initialMetrics = new AdaptiveRTPSession.TimingMetrics();
    assertThat(initialMetrics.sampleCount).isZero();
    assertThat(initialMetrics.averageInterval).isZero();
    assertThat(initialMetrics.averageJitter).isZero();
    assertThat(initialMetrics.maxJitter).isZero();

    // Update with first interval
    AdaptiveRTPSession.TimingMetrics updated1 = initialMetrics.update(100);
    assertThat(updated1.sampleCount).isEqualTo(1);
    assertThat(updated1.averageInterval).isEqualTo(100.0);
    // Jitter compared to expected 20ms interval
    assertThat(updated1.averageJitter).isEqualTo(80.0); // |100 - 20|
    assertThat(updated1.maxJitter).isEqualTo(80);

    // Update with second interval
    AdaptiveRTPSession.TimingMetrics updated2 = updated1.update(30);
    assertThat(updated2.sampleCount).isEqualTo(2);
    assertThat(updated2.averageInterval).isEqualTo(65.0); // (100 + 30) / 2
    assertThat(updated2.averageJitter).isEqualTo(45.0); // (80 + 10) / 2
    assertThat(updated2.maxJitter).isEqualTo(80); // max(80, 10)
  }

  @Test
  @DisplayName("Should provide meaningful string representation of timing metrics")
  void shouldProvideStringRepresentationOfTimingMetrics() {
    AdaptiveRTPSession.TimingMetrics metrics =
        new AdaptiveRTPSession.TimingMetrics().update(100).update(200).update(150);

    String metricsString = metrics.toString();
    assertThat(metricsString)
        .contains("samples=3")
        .contains("avgInterval=150.00ms")
        .contains("avgJitter=")
        .contains("maxJitter=");
  }

  @Test
  @DisplayName("Should handle rapid packet sending without errors")
  void shouldHandleRapidPacketSendingWithoutErrors() {
    Buffer testData = Buffer.buffer("test data");

    // Send many packets rapidly
    for (int i = 0; i < 100; i++) {
      adaptiveSession.sendPacket(testData);
    }

    assertThat(adaptiveSession.getTotalSentPackets()).isEqualTo(100);

    AdaptiveRTPSession.TimingMetrics metrics = adaptiveSession.getTimingMetrics();
    assertThat(metrics.sampleCount).isEqualTo(99); // n-1 intervals for n packets
  }

  @Test
  @DisplayName("Should calculate jitter based on expected RTP interval")
  void shouldCalculateJitterBasedOnExpectedRtpInterval() {
    // Create metrics with known intervals
    AdaptiveRTPSession.TimingMetrics metrics = new AdaptiveRTPSession.TimingMetrics();

    // Test with exact expected interval (should be 0 jitter)
    AdaptiveRTPSession.TimingMetrics exactMetrics = metrics.update(RTPConstants.PACKET_INTERVAL_MS);
    assertThat(exactMetrics.averageJitter).isZero();
    assertThat(exactMetrics.maxJitter).isZero();

    // Test with interval that's off by 5ms
    AdaptiveRTPSession.TimingMetrics jitterMetrics =
        exactMetrics.update(RTPConstants.PACKET_INTERVAL_MS + 5);
    assertThat(jitterMetrics.averageJitter).isEqualTo(2.5); // (0 + 5) / 2
    assertThat(jitterMetrics.maxJitter).isEqualTo(5);
  }

  @Test
  @DisplayName("Should track maximum jitter correctly")
  void shouldTrackMaximumJitterCorrectly() {
    AdaptiveRTPSession.TimingMetrics metrics = new AdaptiveRTPSession.TimingMetrics();

    // Add intervals with varying jitter
    metrics = metrics.update(25); // jitter = 5
    assertThat(metrics.maxJitter).isEqualTo(5);

    metrics = metrics.update(35); // jitter = 15
    assertThat(metrics.maxJitter).isEqualTo(15);

    metrics = metrics.update(22); // jitter = 2
    assertThat(metrics.maxJitter).isEqualTo(15); // Should remain 15

    metrics = metrics.update(50); // jitter = 30
    assertThat(metrics.maxJitter).isEqualTo(30); // Should update to 30
  }

  @Test
  @DisplayName("Should accumulate sample count correctly")
  void shouldAccumulateSampleCountCorrectly() {
    Buffer testData = Buffer.buffer("test data");

    // Send first packet (no metrics yet)
    adaptiveSession.sendPacket(testData);
    assertThat(adaptiveSession.getTimingMetrics().sampleCount).isZero();

    // Send more packets to accumulate samples
    for (int i = 1; i < 10; i++) {
      adaptiveSession.sendPacket(testData);
      assertThat(adaptiveSession.getTimingMetrics().sampleCount).isEqualTo(i);
    }
  }

  @Test
  @DisplayName("Should calculate running averages correctly")
  void shouldCalculateRunningAveragesCorrectly() {
    AdaptiveRTPSession.TimingMetrics metrics = new AdaptiveRTPSession.TimingMetrics();

    // First update: interval=100
    metrics = metrics.update(100);
    assertThat(metrics.averageInterval).isEqualTo(100.0);
    assertThat(metrics.averageJitter).isEqualTo(80.0); // |100 - 20|

    // Second update: interval=50
    metrics = metrics.update(50);
    assertThat(metrics.averageInterval).isEqualTo(75.0); // (100 + 50) / 2
    assertThat(metrics.averageJitter).isEqualTo(55.0); // (80 + 30) / 2

    // Third update: interval=20 (perfect timing)
    metrics = metrics.update(20);
    assertThat(metrics.averageInterval).isCloseTo(56.67, within(0.01)); // (100 + 50 + 20) / 3
    assertThat(metrics.averageJitter).isCloseTo(36.67, within(0.01)); // (80 + 30 + 0) / 3
  }
}
