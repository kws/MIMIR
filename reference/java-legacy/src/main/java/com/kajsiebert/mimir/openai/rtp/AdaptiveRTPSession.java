package com.kajsiebert.mimir.openai.rtp;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mjsip.media.FlowSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

/**
 * Enhanced RTP session with adaptive timing and performance monitoring. Extends the basic
 * RTPSession with jitter detection and adaptive buffering.
 */
public class AdaptiveRTPSession extends RTPSession {
  private static final Logger LOG = LoggerFactory.getLogger(AdaptiveRTPSession.class);

  private final AtomicLong lastSendTime = new AtomicLong(0);
  private final AtomicLong totalSentPackets = new AtomicLong(0);
  private final AtomicReference<TimingMetrics> metrics = new AtomicReference<>(new TimingMetrics());

  // Adaptive buffer for smoother packet flow
  private final CircularBuffer<Buffer> adaptiveBuffer;
  private volatile boolean adaptiveMode = false;

  public AdaptiveRTPSession(Vertx vertx, FlowSpec flowSpec) {
    super(vertx, flowSpec);
    this.adaptiveBuffer = new CircularBuffer<>(10); // Buffer up to 10 packets
    LOG.info("AdaptiveRTPSession initialized with adaptive buffering");
  }

  @Override
  public void sendPacket(Buffer data) {
    long currentTime = System.currentTimeMillis();
    long lastTime = lastSendTime.getAndSet(currentTime);

    if (lastTime > 0) {
      long interval = currentTime - lastTime;
      updateTimingMetrics(interval);

      // Check if we should enable adaptive mode due to timing issues
      if (shouldEnableAdaptiveMode(interval)) {
        enableAdaptiveMode();
      }
    }

    if (adaptiveMode) {
      sendPacketAdaptive(data);
    } else {
      sendPacketDirect(data);
    }

    totalSentPackets.incrementAndGet();
  }

  private void sendPacketDirect(Buffer data) {
    super.sendPacket(data);
  }

  private void sendPacketAdaptive(Buffer data) {
    // Add to buffer and send from buffer to smooth out timing
    adaptiveBuffer.add(data);

    // Send buffered packets if available
    Buffer bufferedPacket = adaptiveBuffer.poll();
    if (bufferedPacket != null) {
      super.sendPacket(bufferedPacket);
    }
  }

  private void updateTimingMetrics(long interval) {
    TimingMetrics current = metrics.get();
    TimingMetrics updated = current.update(interval);
    metrics.set(updated);

    // Log performance warnings
    if (updated.averageJitter > 10.0) {
      LOG.warn("High RTP timing jitter detected: {:.2f}ms average", updated.averageJitter);
    }
  }

  private boolean shouldEnableAdaptiveMode(long interval) {
    TimingMetrics current = metrics.get();

    // Enable adaptive mode if jitter is consistently high
    return current.sampleCount > 10 && current.averageJitter > 5.0;
  }

  private void enableAdaptiveMode() {
    if (!adaptiveMode) {
      adaptiveMode = true;
      LOG.info("Enabled adaptive RTP timing mode due to detected jitter");
    }
  }

  public TimingMetrics getTimingMetrics() {
    return metrics.get();
  }

  public long getTotalSentPackets() {
    return totalSentPackets.get();
  }

  public boolean isAdaptiveModeEnabled() {
    return adaptiveMode;
  }

  /** Immutable timing metrics tracking */
  public static class TimingMetrics {
    public final long sampleCount;
    public final double averageInterval;
    public final double averageJitter;
    public final long maxJitter;

    public TimingMetrics() {
      this(0, 0.0, 0.0, 0);
    }

    private TimingMetrics(
        long sampleCount, double averageInterval, double averageJitter, long maxJitter) {
      this.sampleCount = sampleCount;
      this.averageInterval = averageInterval;
      this.averageJitter = averageJitter;
      this.maxJitter = maxJitter;
    }

    public TimingMetrics update(long interval) {
      long expectedInterval = RTPConstants.PACKET_INTERVAL_MS;
      long jitter = Math.abs(interval - expectedInterval);

      long newSampleCount = sampleCount + 1;
      double newAverageInterval = (averageInterval * sampleCount + interval) / newSampleCount;
      double newAverageJitter = (averageJitter * sampleCount + jitter) / newSampleCount;
      long newMaxJitter = Math.max(maxJitter, jitter);

      return new TimingMetrics(newSampleCount, newAverageInterval, newAverageJitter, newMaxJitter);
    }

    @Override
    public String toString() {
      return String.format(
          "TimingMetrics{samples=%d, avgInterval=%.2fms, avgJitter=%.2fms, maxJitter=%dms}",
          sampleCount, averageInterval, averageJitter, maxJitter);
    }
  }

  /** Simple circular buffer for packet smoothing */
  private static class CircularBuffer<T> {
    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int size = 0;

    public CircularBuffer(int capacity) {
      this.capacity = capacity;
      this.buffer = new Object[capacity];
    }

    public synchronized void add(T item) {
      if (size == capacity) {
        // Buffer full, overwrite oldest
        head = (head + 1) % capacity;
      } else {
        size++;
      }

      buffer[tail] = item;
      tail = (tail + 1) % capacity;
    }

    @SuppressWarnings("unchecked")
    public synchronized T poll() {
      if (size == 0) {
        return null;
      }

      T item = (T) buffer[head];
      buffer[head] = null;
      head = (head + 1) % capacity;
      size--;

      return item;
    }

    public synchronized int size() {
      return size;
    }
  }
}
