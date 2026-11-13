package com.togethermarket.recommendation.service.job;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Opt-in bounded workers + expiry sweep. The application must close this runtime on shutdown. */
public final class RecommendationJobRuntime implements AutoCloseable {
  private static final System.Logger LOG =
      System.getLogger(RecommendationJobRuntime.class.getName());
  private final ThreadPoolExecutor workers;
  private final ScheduledThreadPoolExecutor recovery;
  private final RecommendationJobRunner runner;

  public RecommendationJobRuntime(
      RecommendationJobStore store,
      RecommendationComputation computation,
      Clock clock,
      RecommendationJobRunner.Policy policy,
      int parallelism,
      int queueCapacity,
      Duration recoveryInterval) {
    Objects.requireNonNull(recoveryInterval);
    if (parallelism < 1 || queueCapacity < 1 || recoveryInterval.toMillis() < 1)
      throw new IllegalArgumentException("Invalid executor limits");
    workers =
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().daemon().name("recommendation-worker-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    recovery =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("recommendation-recovery-", 0).factory());
    recovery.setRemoveOnCancelPolicy(true);
    runner = new RecommendationJobRunner(store, computation, workers, clock, policy);
    // Zero initial delay includes restart recovery. A failed sweep must not suppress future sweeps.
    recovery.scheduleWithFixedDelay(
        () -> {
          try {
            runner.recoverExpired();
          } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Recommendation expiry sweep failed; will retry");
          }
        },
        0,
        recoveryInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  public RecommendationJobRunner.DispatchResult afterCommit(Long requestId) {
    return runner.afterCommit(requestId);
  }

  @Override
  public void close() {
    recovery.shutdownNow();
    workers.shutdownNow();
    runner.cancelLocalWork();
  }
}
