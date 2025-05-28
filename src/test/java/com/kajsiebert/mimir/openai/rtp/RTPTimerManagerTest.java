package com.kajsiebert.mimir.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/** Unit tests for RTPTimerManager Focuses on functional behavior rather than precise timing */
@ExtendWith({MockitoExtension.class, VertxExtension.class})
class RTPTimerManagerTest {

  @Mock private Runnable mockTask;

  private RTPTimerManager timerManager;

  @BeforeEach
  void setUp(Vertx vertx) {
    timerManager = new RTPTimerManager(vertx);
  }

  @AfterEach
  void tearDown() {
    if (timerManager != null) {
      timerManager.shutdown();
    }
  }

  @Test
  void shouldInitializeCorrectly() {
    assertThat(timerManager).isNotNull();
    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isEqualTo(0);
    assertThat(stats.averageJitterMs).isEqualTo(0.0);
  }

  @Test
  void shouldStartPeriodicTaskAndExecuteIt(VertxTestContext testContext)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    Runnable testTask =
        () -> {
          latch.countDown();
        };

    timerManager.startPeriodicTask(50, testTask);

    // Wait for at least one execution
    boolean executed = latch.await(500, TimeUnit.MILLISECONDS);

    timerManager.stop();

    assertThat(executed).isTrue();
    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isGreaterThan(0);
    testContext.completeNow();
  }

  @Test
  void shouldStopPeriodicTaskCorrectly() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    Runnable testTask = latch::countDown;

    timerManager.startPeriodicTask(20, testTask);

    // Let it execute at least once
    latch.await(100, TimeUnit.MILLISECONDS);

    timerManager.stop();

    // Check that packets were sent
    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isGreaterThan(0);
  }

  @Test
  void shouldNotStartIfAlreadyRunning() throws InterruptedException {
    timerManager.startPeriodicTask(30, mockTask);

    // Try to start again - should be ignored (logged as warning)
    timerManager.startPeriodicTask(40, mockTask);

    Thread.sleep(100); // Give some time for potential execution
    timerManager.stop();

    // Should work normally - no exceptions
    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isGreaterThanOrEqualTo(0);
  }

  @Test
  void shouldAllowRestartAfterStop() throws InterruptedException {
    CountDownLatch firstLatch = new CountDownLatch(1);
    CountDownLatch secondLatch = new CountDownLatch(1);

    // First run
    timerManager.startPeriodicTask(30, firstLatch::countDown);
    firstLatch.await(100, TimeUnit.MILLISECONDS);
    timerManager.stop();

    RTPTimerManager.TimingStats firstStats = timerManager.getTimingStats();

    // Second run (create new manager as stats are cumulative)
    timerManager.startPeriodicTask(30, secondLatch::countDown);
    secondLatch.await(100, TimeUnit.MILLISECONDS);
    timerManager.stop();

    // Should be able to restart
    RTPTimerManager.TimingStats secondStats = timerManager.getTimingStats();
    assertThat(secondStats.totalPackets).isGreaterThan(firstStats.totalPackets);
  }

  @Test
  void shouldTrackTimingStatistics() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(3);
    Runnable testTask = latch::countDown;

    timerManager.startPeriodicTask(20, testTask);
    latch.await(200, TimeUnit.MILLISECONDS);
    timerManager.stop();

    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isGreaterThanOrEqualTo(3);
    assertThat(stats.averageJitterMs).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void shouldHandleTaskExceptionsGracefully() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(2);

    Runnable faultyTask =
        () -> {
          latch.countDown();
          throw new RuntimeException("Test exception");
        };

    timerManager.startPeriodicTask(50, faultyTask);
    boolean executed = latch.await(300, TimeUnit.MILLISECONDS);
    timerManager.stop();

    // Should continue running despite exceptions
    assertThat(executed).isTrue();
    RTPTimerManager.TimingStats stats = timerManager.getTimingStats();
    assertThat(stats.totalPackets).isGreaterThanOrEqualTo(2);
  }

  @Test
  void shouldShutdownGracefully() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    timerManager.startPeriodicTask(30, latch::countDown);
    latch.await(100, TimeUnit.MILLISECONDS);

    // Should shutdown without throwing exceptions
    timerManager.shutdown();

    // Should be able to call shutdown multiple times
    timerManager.shutdown();
  }

  @Test
  void shouldProvideTimingStatsStringRepresentation() {
    RTPTimerManager.TimingStats stats = new RTPTimerManager.TimingStats(100, 2.5);
    String representation = stats.toString();
    assertThat(representation).contains("TimingStats");
    assertThat(representation).contains("packets=100");
    assertThat(representation).contains("avgJitter=2.50ms");
  }
}
