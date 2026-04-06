package com.kajsiebert.mimir.openai.rtp;

/**
 * Configuration class for RTP timing parameters. Allows fine-tuning of timing behavior for
 * different network conditions and requirements.
 */
public class RTPTimingConfig {

  // Timing thresholds
  private final long jitterWarningThresholdMs;
  private final long jitterErrorThresholdMs;
  private final int adaptiveBufferSize;
  private final boolean enableAdaptiveMode;

  // Thread priority settings
  private final int rtpThreadPriority;
  private final boolean useDedicatedThread;

  // Performance monitoring
  private final boolean enableTimingMetrics;
  private final long metricsReportingIntervalMs;

  private RTPTimingConfig(Builder builder) {
    this.jitterWarningThresholdMs = builder.jitterWarningThresholdMs;
    this.jitterErrorThresholdMs = builder.jitterErrorThresholdMs;
    this.adaptiveBufferSize = builder.adaptiveBufferSize;
    this.enableAdaptiveMode = builder.enableAdaptiveMode;
    this.rtpThreadPriority = builder.rtpThreadPriority;
    this.useDedicatedThread = builder.useDedicatedThread;
    this.enableTimingMetrics = builder.enableTimingMetrics;
    this.metricsReportingIntervalMs = builder.metricsReportingIntervalMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Default configuration optimized for real-time audio */
  public static RTPTimingConfig defaultConfig() {
    return builder()
        .jitterWarningThreshold(5)
        .jitterErrorThreshold(15)
        .adaptiveBufferSize(10)
        .enableAdaptiveMode(true)
        .rtpThreadPriority(Thread.MAX_PRIORITY)
        .useDedicatedThread(true)
        .enableTimingMetrics(true)
        .metricsReportingInterval(30000) // 30 seconds
        .build();
  }

  /** Configuration optimized for low-latency environments */
  public static RTPTimingConfig lowLatencyConfig() {
    return builder()
        .jitterWarningThreshold(2)
        .jitterErrorThreshold(5)
        .adaptiveBufferSize(5)
        .enableAdaptiveMode(false) // Disable buffering for lowest latency
        .rtpThreadPriority(Thread.MAX_PRIORITY)
        .useDedicatedThread(true)
        .enableTimingMetrics(true)
        .metricsReportingInterval(10000) // 10 seconds
        .build();
  }

  /** Configuration optimized for unreliable networks */
  public static RTPTimingConfig robustConfig() {
    return builder()
        .jitterWarningThreshold(10)
        .jitterErrorThreshold(25)
        .adaptiveBufferSize(20)
        .enableAdaptiveMode(true)
        .rtpThreadPriority(Thread.NORM_PRIORITY + 2)
        .useDedicatedThread(true)
        .enableTimingMetrics(true)
        .metricsReportingInterval(60000) // 1 minute
        .build();
  }

  // Getters
  public long getJitterWarningThresholdMs() {
    return jitterWarningThresholdMs;
  }

  public long getJitterErrorThresholdMs() {
    return jitterErrorThresholdMs;
  }

  public int getAdaptiveBufferSize() {
    return adaptiveBufferSize;
  }

  public boolean isAdaptiveModeEnabled() {
    return enableAdaptiveMode;
  }

  public int getRtpThreadPriority() {
    return rtpThreadPriority;
  }

  public boolean isUseDedicatedThread() {
    return useDedicatedThread;
  }

  public boolean isTimingMetricsEnabled() {
    return enableTimingMetrics;
  }

  public long getMetricsReportingIntervalMs() {
    return metricsReportingIntervalMs;
  }

  public static class Builder {
    private long jitterWarningThresholdMs = 5;
    private long jitterErrorThresholdMs = 15;
    private int adaptiveBufferSize = 10;
    private boolean enableAdaptiveMode = true;
    private int rtpThreadPriority = Thread.MAX_PRIORITY;
    private boolean useDedicatedThread = true;
    private boolean enableTimingMetrics = true;
    private long metricsReportingIntervalMs = 30000;

    public Builder jitterWarningThreshold(long thresholdMs) {
      this.jitterWarningThresholdMs = thresholdMs;
      return this;
    }

    public Builder jitterErrorThreshold(long thresholdMs) {
      this.jitterErrorThresholdMs = thresholdMs;
      return this;
    }

    public Builder adaptiveBufferSize(int size) {
      this.adaptiveBufferSize = size;
      return this;
    }

    public Builder enableAdaptiveMode(boolean enable) {
      this.enableAdaptiveMode = enable;
      return this;
    }

    public Builder rtpThreadPriority(int priority) {
      this.rtpThreadPriority =
          Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority));
      return this;
    }

    public Builder useDedicatedThread(boolean use) {
      this.useDedicatedThread = use;
      return this;
    }

    public Builder enableTimingMetrics(boolean enable) {
      this.enableTimingMetrics = enable;
      return this;
    }

    public Builder metricsReportingInterval(long intervalMs) {
      this.metricsReportingIntervalMs = intervalMs;
      return this;
    }

    public RTPTimingConfig build() {
      return new RTPTimingConfig(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "RTPTimingConfig{"
            + "jitterWarning=%dms, jitterError=%dms, "
            + "adaptiveBuffer=%d, adaptiveMode=%s, "
            + "threadPriority=%d, dedicatedThread=%s, "
            + "metrics=%s, metricsInterval=%dms}",
        jitterWarningThresholdMs,
        jitterErrorThresholdMs,
        adaptiveBufferSize,
        enableAdaptiveMode,
        rtpThreadPriority,
        useDedicatedThread,
        enableTimingMetrics,
        metricsReportingIntervalMs);
  }
}
