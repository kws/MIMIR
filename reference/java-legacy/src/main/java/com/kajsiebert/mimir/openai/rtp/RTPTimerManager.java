package com.kajsiebert.mimir.openai.rtp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;

/**
 * High-priority timer manager for RTP packet timing. Uses a dedicated thread pool with real-time
 * scheduling to ensure precise timing independent of Vert.x event loop performance.
 */
public class RTPTimerManager {
  private static final Logger LOG = LoggerFactory.getLogger(RTPTimerManager.class);

  private final ScheduledExecutorService rtpScheduler;
  private final Vertx vertx;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicLong totalPackets = new AtomicLong(0);
  private final AtomicLong totalJitter = new AtomicLong(0);

  private ScheduledFuture<?> rtpTask;
  private long lastExecutionTime = 0;

  public RTPTimerManager(Vertx vertx) {
    this.vertx = vertx;

    // Create dedicated thread pool for RTP timing with high priority
    this.rtpScheduler = Executors.newSingleThreadScheduledExecutor(new RTPThreadFactory());

    LOG.info("RTPTimerManager initialized with dedicated scheduler");
  }

  /** Start periodic RTP packet sending with microsecond-level precision */
  public void startPeriodicTask(long intervalMs, Runnable task) {
    if (isRunning.compareAndSet(false, true)) {
      lastExecutionTime = System.currentTimeMillis();

      rtpTask =
          rtpScheduler.scheduleAtFixedRate(
              () -> {
                long currentTime = System.currentTimeMillis();
                long actualInterval = currentTime - lastExecutionTime;
                long jitter = Math.abs(actualInterval - intervalMs);

                // Track timing statistics
                totalPackets.incrementAndGet();
                totalJitter.addAndGet(jitter);

                // Log significant jitter (> 5ms indicates timing issues)
                if (jitter > 5) {
                  LOG.warn(
                      "RTP timer jitter detected: {}ms (expected: {}ms, actual: {}ms)",
                      jitter,
                      intervalMs,
                      actualInterval);
                }

                lastExecutionTime = currentTime;

                // Execute task with exception handling to prevent termination
                try {
                  task.run();
                } catch (Exception e) {
                  LOG.warn("Exception in RTP task execution, continuing: {}", e.getMessage(), e);
                }
              },
              0,
              intervalMs,
              TimeUnit.MILLISECONDS);

      LOG.info("RTP periodic task started with {}ms interval", intervalMs);
    } else {
      LOG.warn("RTP timer manager already running");
    }
  }

  /** Stop the periodic RTP task */
  public void stop() {
    if (isRunning.compareAndSet(true, false)) {
      if (rtpTask != null && !rtpTask.isCancelled()) {
        rtpTask.cancel(false);
        rtpTask = null;
      }

      // Log timing statistics
      long packets = totalPackets.get();
      if (packets > 0) {
        double avgJitter = (double) totalJitter.get() / packets;
        LOG.info(
            "RTP timer stopped. Packets sent: {}, Average jitter: {}ms",
            packets,
            String.format("%.2f", avgJitter));
      }

      LOG.info("RTP periodic task stopped");
    }
  }

  /** Shutdown the timer manager and release resources */
  public void shutdown() {
    stop();
    rtpScheduler.shutdown();
    try {
      if (!rtpScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        rtpScheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      rtpScheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    LOG.info("RTPTimerManager shutdown complete");
  }

  /** Get timing statistics */
  public TimingStats getTimingStats() {
    long packets = totalPackets.get();
    double avgJitter = packets > 0 ? (double) totalJitter.get() / packets : 0.0;
    return new TimingStats(packets, avgJitter);
  }

  /** Custom thread factory for RTP timing threads with high priority */
  private static class RTPThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r, "RTP-Timer-Thread");
      thread.setDaemon(true);
      thread.setPriority(Thread.MAX_PRIORITY); // Highest possible priority
      return thread;
    }
  }

  /** Timing statistics container */
  public static class TimingStats {
    public final long totalPackets;
    public final double averageJitterMs;

    public TimingStats(long totalPackets, double averageJitterMs) {
      this.totalPackets = totalPackets;
      this.averageJitterMs = averageJitterMs;
    }

    @Override
    public String toString() {
      return String.format(
          "TimingStats{packets=%d, avgJitter=%.2fms}", totalPackets, averageJitterMs);
    }
  }
}
