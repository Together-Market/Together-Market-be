package com.togethermarket.recommendation.service.job;

import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.job.RecommendationJobStore.Lease;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Explicitly assembled service: no controller, automatic scheduling, shared entity or Spring bean.
 */
public final class RecommendationJobRunner {
  private static final System.Logger LOG =
      System.getLogger(RecommendationJobRunner.class.getName());
  private final RecommendationJobStore store;
  private final RecommendationComputation computation;
  private final Executor executor;
  private final Clock clock;
  private final Policy policy;
  private final ConcurrentHashMap<UUID, Work> active = new ConcurrentHashMap<>();

  public record Policy(Duration timeout, int recoveryBatchSize) {
    public Policy {
      Objects.requireNonNull(timeout);
      if (timeout.isZero() || timeout.isNegative() || recoveryBatchSize < 1)
        throw new IllegalArgumentException("Invalid job policy");
    }

    public static Policy defaults() {
      return new Policy(Duration.ofSeconds(40), 100);
    }
  }

  public enum DispatchResult {
    SUBMITTED,
    NOT_CLAIMED,
    REJECTED
  }

  public RecommendationJobRunner(
      RecommendationJobStore store,
      RecommendationComputation computation,
      Executor executor,
      Clock clock,
      Policy policy) {
    this.store = Objects.requireNonNull(store);
    this.computation = Objects.requireNonNull(computation);
    this.executor = Objects.requireNonNull(executor);
    this.clock = Objects.requireNonNull(clock);
    this.policy = Objects.requireNonNull(policy);
  }

  /**
   * Invoke ONLY after purchase-request transaction commits. Duplicate notifications are harmless.
   */
  public DispatchResult afterCommit(Long requestId) {
    if (requestId == null || requestId <= 0)
      throw new IllegalArgumentException("Positive request ID required");
    UUID token = UUID.randomUUID();
    var claimed = store.claim(requestId, token, clock.instant(), policy.timeout());
    if (claimed.isEmpty()) return DispatchResult.NOT_CLAIMED;
    Lease lease = claimed.get();
    if (!lease.requestId().equals(requestId) || !lease.token().equals(token))
      throw new IllegalStateException("Job store returned an unrelated lease");
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              execute(lease);
              return null;
            }) {
          @Override
          protected void done() {
            active.remove(lease.token());
          }
        };
    active.put(lease.token(), new Work(lease, task));
    try {
      executor.execute(task);
      return DispatchResult.SUBMITTED;
    } catch (RejectedExecutionException failure) {
      task.cancel(false);
      failSafely(lease, RecommendationFailureCode.DISPATCH_REJECTED);
      return DispatchResult.REJECTED;
    }
  }

  /**
   * Run on startup and periodically. Also recovers rows whose after-commit notification was lost.
   */
  public int recoverExpired() {
    var now = clock.instant();
    // Cancellation is best effort only. Persisted token/deadline checks guard ignored interrupts.
    for (Work work : active.values()) {
      if (!now.isBefore(work.lease().deadline())) {
        work.task().cancel(true);
        failSafely(work.lease(), RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
      }
    }
    return store.expire(now, policy.timeout(), policy.recoveryBatchSize());
  }

  /** Interrupt local work on orderly shutdown; a process crash is handled by durable expiry. */
  public void cancelLocalWork() {
    for (Work work : active.values()) {
      work.task().cancel(true);
      failSafely(work.lease(), RecommendationFailureCode.WORKER_INTERRUPTED);
    }
  }

  private void execute(Lease lease) {
    if (!clock.instant().isBefore(lease.deadline())) {
      failSafely(lease, RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
      return;
    }
    List<RankedCandidate> result;
    try {
      result = List.copyOf(computation.compute(lease.requestId()));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      failSafely(lease, RecommendationFailureCode.WORKER_INTERRUPTED);
      return;
    } catch (RecommendationComputation.Failure failure) {
      failSafely(lease, failure.code());
      return;
    } catch (HttpTimeoutException | TimeoutException failure) {
      failSafely(lease, RecommendationFailureCode.AI_TIMEOUT);
      return;
    } catch (Exception failure) {
      failSafely(
          lease,
          Thread.currentThread().isInterrupted()
              ? RecommendationFailureCode.WORKER_INTERRUPTED
              : RecommendationFailureCode.RECOMMENDATION_FAILED);
      return;
    }
    if (Thread.currentThread().isInterrupted()) {
      failSafely(lease, RecommendationFailureCode.WORKER_INTERRUPTED);
      return;
    }
    try {
      // The store rechecks token/deadline atomically; this call may return false after expiry.
      store.complete(lease, result, clock.instant());
    } catch (RuntimeException failure) {
      failSafely(lease, RecommendationFailureCode.RESULT_PERSISTENCE_FAILED);
    }
  }

  private void failSafely(Lease lease, RecommendationFailureCode code) {
    try {
      store.fail(lease, code, clock.instant());
    } catch (RuntimeException unavailableStore) {
      // No request answers, exception bodies or credentials in logs. Recovery will retry expiry.
      LOG.log(
          System.Logger.Level.ERROR,
          "Recommendation state write failed; request={0}, code={1}",
          lease.requestId(),
          code);
    }
  }

  private record Work(Lease lease, FutureTask<Void> task) {}
}
