package com.kajsiebert.mimir.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RTPTimingConfig Tests")
class RTPTimingConfigTest {

  @Test
  @DisplayName("Should create default configuration with expected values")
  void shouldCreateDefaultConfiguration() {
    RTPTimingConfig config = RTPTimingConfig.defaultConfig();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(5);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(15);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(10);
    assertThat(config.isAdaptiveModeEnabled()).isTrue();
    assertThat(config.getRtpThreadPriority()).isEqualTo(Thread.MAX_PRIORITY);
    assertThat(config.isUseDedicatedThread()).isTrue();
    assertThat(config.isTimingMetricsEnabled()).isTrue();
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(30000);
  }

  @Test
  @DisplayName("Should create low latency configuration with expected values")
  void shouldCreateLowLatencyConfiguration() {
    RTPTimingConfig config = RTPTimingConfig.lowLatencyConfig();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(2);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(5);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(5);
    assertThat(config.isAdaptiveModeEnabled()).isFalse(); // Disabled for lowest latency
    assertThat(config.getRtpThreadPriority()).isEqualTo(Thread.MAX_PRIORITY);
    assertThat(config.isUseDedicatedThread()).isTrue();
    assertThat(config.isTimingMetricsEnabled()).isTrue();
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(10000);
  }

  @Test
  @DisplayName("Should create robust configuration with expected values")
  void shouldCreateRobustConfiguration() {
    RTPTimingConfig config = RTPTimingConfig.robustConfig();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(10);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(25);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(20);
    assertThat(config.isAdaptiveModeEnabled()).isTrue();
    assertThat(config.getRtpThreadPriority()).isEqualTo(Thread.NORM_PRIORITY + 2);
    assertThat(config.isUseDedicatedThread()).isTrue();
    assertThat(config.isTimingMetricsEnabled()).isTrue();
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(60000);
  }

  @Test
  @DisplayName("Should build custom configuration using builder")
  void shouldBuildCustomConfiguration() {
    RTPTimingConfig config =
        RTPTimingConfig.builder()
            .jitterWarningThreshold(8)
            .jitterErrorThreshold(20)
            .adaptiveBufferSize(15)
            .enableAdaptiveMode(false)
            .rtpThreadPriority(Thread.NORM_PRIORITY + 1)
            .useDedicatedThread(false)
            .enableTimingMetrics(false)
            .metricsReportingInterval(45000)
            .build();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(8);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(20);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(15);
    assertThat(config.isAdaptiveModeEnabled()).isFalse();
    assertThat(config.getRtpThreadPriority()).isEqualTo(Thread.NORM_PRIORITY + 1);
    assertThat(config.isUseDedicatedThread()).isFalse();
    assertThat(config.isTimingMetricsEnabled()).isFalse();
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(45000);
  }

  @Test
  @DisplayName("Should use default builder values when not explicitly set")
  void shouldUseDefaultBuilderValues() {
    RTPTimingConfig config = RTPTimingConfig.builder().build();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(5);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(15);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(10);
    assertThat(config.isAdaptiveModeEnabled()).isTrue();
    assertThat(config.getRtpThreadPriority()).isEqualTo(Thread.MAX_PRIORITY);
    assertThat(config.isUseDedicatedThread()).isTrue();
    assertThat(config.isTimingMetricsEnabled()).isTrue();
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(30000);
  }

  @Test
  @DisplayName("Should clamp thread priority to valid range")
  void shouldClampThreadPriorityToValidRange() {
    // Test priority below minimum
    RTPTimingConfig configLow =
        RTPTimingConfig.builder().rtpThreadPriority(Thread.MIN_PRIORITY - 1).build();
    assertThat(configLow.getRtpThreadPriority()).isEqualTo(Thread.MIN_PRIORITY);

    // Test priority above maximum
    RTPTimingConfig configHigh =
        RTPTimingConfig.builder().rtpThreadPriority(Thread.MAX_PRIORITY + 1).build();
    assertThat(configHigh.getRtpThreadPriority()).isEqualTo(Thread.MAX_PRIORITY);

    // Test valid priority
    RTPTimingConfig configValid =
        RTPTimingConfig.builder().rtpThreadPriority(Thread.NORM_PRIORITY).build();
    assertThat(configValid.getRtpThreadPriority()).isEqualTo(Thread.NORM_PRIORITY);
  }

  @Test
  @DisplayName("Should provide meaningful toString representation")
  void shouldProvideStringRepresentation() {
    RTPTimingConfig config = RTPTimingConfig.defaultConfig();
    String toString = config.toString();

    assertThat(toString)
        .contains("RTPTimingConfig{")
        .contains("jitterWarning=5ms")
        .contains("jitterError=15ms")
        .contains("adaptiveBuffer=10")
        .contains("adaptiveMode=true")
        .contains("threadPriority=" + Thread.MAX_PRIORITY)
        .contains("dedicatedThread=true")
        .contains("metrics=true")
        .contains("metricsInterval=30000ms");
  }

  @Test
  @DisplayName("Should support method chaining in builder")
  void shouldSupportMethodChaining() {
    RTPTimingConfig.Builder builder = RTPTimingConfig.builder();

    // Verify that each method returns the builder instance for chaining
    RTPTimingConfig config =
        builder
            .jitterWarningThreshold(1)
            .jitterErrorThreshold(2)
            .adaptiveBufferSize(3)
            .enableAdaptiveMode(true)
            .rtpThreadPriority(5)
            .useDedicatedThread(true)
            .enableTimingMetrics(true)
            .metricsReportingInterval(1000)
            .build();

    assertThat(config.getJitterWarningThresholdMs()).isEqualTo(1);
    assertThat(config.getJitterErrorThresholdMs()).isEqualTo(2);
    assertThat(config.getAdaptiveBufferSize()).isEqualTo(3);
    assertThat(config.getMetricsReportingIntervalMs()).isEqualTo(1000);
  }

  @Test
  @DisplayName("Should handle edge case values correctly")
  void shouldHandleEdgeCaseValues() {
    RTPTimingConfig config =
        RTPTimingConfig.builder()
            .jitterWarningThreshold(0)
            .jitterErrorThreshold(0)
            .adaptiveBufferSize(0)
            .metricsReportingInterval(0)
            .build();

    assertThat(config.getJitterWarningThresholdMs()).isZero();
    assertThat(config.getJitterErrorThresholdMs()).isZero();
    assertThat(config.getAdaptiveBufferSize()).isZero();
    assertThat(config.getMetricsReportingIntervalMs()).isZero();
  }

  @Test
  @DisplayName("Should create new builder instance each time")
  void shouldCreateNewBuilderInstance() {
    RTPTimingConfig.Builder builder1 = RTPTimingConfig.builder();
    RTPTimingConfig.Builder builder2 = RTPTimingConfig.builder();

    assertThat(builder1).isNotSameAs(builder2);
  }
}
