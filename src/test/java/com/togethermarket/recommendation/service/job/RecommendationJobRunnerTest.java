package com.togethermarket.recommendation.service.job;

import static com.togethermarket.recommendation.service.job.JobFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.RecommendationInput;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecommendationJobRunnerTest {
  final MutableClock clock = new MutableClock();
  final MemoryStore store = new MemoryStore();
  final QueuedExecutor executor = new QueuedExecutor();
  final AtomicInteger calls = new AtomicInteger();
  final RecommendationJobRunner runner =
      runner(
          id -> {
            calls.incrementAndGet();
            return List.of(result());
          });

  RecommendationJobRunnerTest() {
    store.add(1);
  }

  RecommendationJobRunner runner(RecommendationComputation computation) {
    return new RecommendationJobRunner(
        store, computation, executor, clock, RecommendationJobRunner.Policy.defaults());
  }

  static RankedCandidate result() {
    return new RankedCandidate(
        new RecommendationInput.Candidate(
            "p_1_2",
            List.of(
                new RecommendationInput.Product("1", "1", "꽃", "", "꽃집", 3000, true),
                new RecommendationInput.Product("2", "2", "케이크", "", "제과", 4000, true)),
            100.0,
            true),
        "선물 조합",
        80,
        "baseline",
        "manual-v1");
  }

  @Test
  void computationRunsOnlyOnWorkerAndCommitsCandidatesWithReady() {
    assertThat(runner.afterCommit(1L)).isEqualTo(RecommendationJobRunner.DispatchResult.SUBMITTED);
    assertThat(calls.get()).isZero();
    assertThat(store.state(1).status()).isEqualTo("RECOMMENDING");
    executor.runNext();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(store.state(1).status()).isEqualTo("READY");
    assertThat(store.state(1).failureCode()).isNull();
    assertThat(store.result(1)).containsExactly(result());
  }

  @Test
  void emptyCandidatesAreSuccessfulReadyNotFailed() {
    runner(id -> List.of()).afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).status()).isEqualTo("READY");
    assertThat(store.result(1)).isEmpty();
  }

  @Test
  void duplicateNotificationsAndTerminalRequestsNeverExecuteTwice() {
    runner.afterCommit(1L);
    assertThat(runner.afterCommit(1L))
        .isEqualTo(RecommendationJobRunner.DispatchResult.NOT_CLAIMED);
    executor.runNext();
    assertThat(runner.afterCommit(1L))
        .isEqualTo(RecommendationJobRunner.DispatchResult.NOT_CLAIMED);
    assertThat(runner.afterCommit(999L))
        .isEqualTo(RecommendationJobRunner.DispatchResult.NOT_CLAIMED);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(store.commits).isEqualTo(1);
  }

  @Test
  void concurrentInstancesShareOneDurableClaim() throws Exception {
    var second =
        runner(
            id -> {
              calls.incrementAndGet();
              return List.of();
            });
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return runner.afterCommit(1L);
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return second.afterCommit(1L);
              });
      gate.countDown();
      assertThat(List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              RecommendationJobRunner.DispatchResult.SUBMITTED,
              RecommendationJobRunner.DispatchResult.NOT_CLAIMED);
    }
    assertThat(executor.tasks).hasSize(1);
    executor.runNext();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void executorRejectionBecomesFailedWithoutComputation() {
    var rejected =
        new RecommendationJobRunner(
            store,
            id -> {
              throw new AssertionError("Must not run");
            },
            task -> {
              throw new RejectedExecutionException();
            },
            clock,
            RecommendationJobRunner.Policy.defaults());
    assertThat(rejected.afterCommit(1L)).isEqualTo(RecommendationJobRunner.DispatchResult.REJECTED);
    assertThat(store.state(1).failureCode()).isEqualTo(RecommendationFailureCode.DISPATCH_REJECTED);
    assertThat(store.result(1)).isNull();
  }

  @Test
  void queuedTaskCanExpireWithoutEverCallingAi() {
    runner.afterCommit(1L);
    clock.advance(TIMEOUT);
    runner.recoverExpired();
    executor.runNext();
    assertThat(calls.get()).isZero();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
    assertThat(store.result(1)).isNull();
  }

  @Test
  void deadlineIsCheckedBeforeComputationEvenWithoutSweep() {
    runner.afterCommit(1L);
    clock.advance(TIMEOUT);
    executor.runNext();
    assertThat(calls.get()).isZero();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
  }

  @Test
  void computationFinishingAtDeadlineCannotStoreCandidates() {
    runner(
            id -> {
              clock.advance(TIMEOUT);
              return List.of(result());
            })
        .afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
    assertThat(store.result(1)).isNull();
    assertThat(store.commits).isZero();
  }

  @Test
  void lostNotificationAndCrashedWorkerAreRecoveredByNewInstanceInBatches() {
    store.add(2);
    store.add(3);
    store.claim(2L, UUID.randomUUID(), START, TIMEOUT); // Worker process disappeared.
    var restarted =
        new RecommendationJobRunner(
            store,
            id -> {
              throw new AssertionError("No recomputation");
            },
            executor,
            clock,
            new RecommendationJobRunner.Policy(TIMEOUT, 2));
    clock.advance(TIMEOUT);
    assertThat(restarted.recoverExpired()).isEqualTo(2);
    assertThat(restarted.recoverExpired()).isEqualTo(1);
    assertThat(restarted.recoverExpired()).isZero();
    for (long id = 1; id <= 3; id++)
      assertThat(store.state(id).failureCode())
          .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
    assertThat(executor.tasks).isEmpty();
  }

  @Test
  void typedFailuresAndTimeoutHaveSafeStableCodes() {
    runner(
            id -> {
              throw new RecommendationComputation.Failure(
                  RecommendationFailureCode.AI_RESPONSE_INVALID);
            })
        .afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.AI_RESPONSE_INVALID);
    store.add(2);
    runner(
            id -> {
              throw new TimeoutException("private provider data");
            })
        .afterCommit(2L);
    executor.runNext();
    assertThat(store.state(2).failureCode()).isEqualTo(RecommendationFailureCode.AI_TIMEOUT);
  }

  @Test
  void unknownErrorsAndNullResultFailWithoutLeakingRawMessages() {
    runner(
            id -> {
              throw new IllegalArgumentException("secret data must never become failure_code");
            })
        .afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_FAILED);
    store.add(2);
    runner(id -> null).afterCommit(2L);
    executor.runNext();
    assertThat(store.state(2).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_FAILED);
  }

  @Test
  void insertFailureLeavesNoPartialResultsAndMarksFailed() {
    store.failCompletion = true;
    runner.afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RESULT_PERSISTENCE_FAILED);
    assertThat(store.result(1)).isNull();
  }

  @Test
  void lostCommitAcknowledgmentCannotOverwriteAlreadyCommittedReady() {
    store.loseCommitAcknowledgment = true;
    runner.afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).status()).isEqualTo("READY");
    assertThat(store.result(1)).containsExactly(result());
    assertThat(store.commits).isEqualTo(1);
  }

  @Test
  void failedStatusWriteIsRecoveredAfterDatabaseReturns() {
    store.failWrite = true;
    runner(
            id -> {
              throw new RuntimeException();
            })
        .afterCommit(1L);
    executor.runNext();
    assertThat(store.state(1).status()).isEqualTo("RECOMMENDING");
    store.failWrite = false;
    clock.advance(TIMEOUT);
    runner.recoverExpired();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
  }

  @Test
  void interruptionIsPreservedAndDoesNotPublishSuccess() {
    runner(
            id -> {
              throw new InterruptedException();
            })
        .afterCommit(1L);
    try {
      executor.runNext();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(store.state(1).failureCode())
          .isEqualTo(RecommendationFailureCode.WORKER_INTERRUPTED);
      assertThat(store.result(1)).isNull();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void workerIgnoringCancellationStillCannotResurrectExpiredRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var subject =
          new RecommendationJobRunner(
              store,
              id -> {
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                  try {
                    if (!release.await(3, TimeUnit.SECONDS))
                      throw new IllegalStateException("Test timed out");
                    waiting = false;
                  } catch (InterruptedException ignored) {
                    /* Deliberately simulate a non-cooperative client. */
                  }
                }
                return List.of(result());
              },
              pool,
              clock,
              RecommendationJobRunner.Policy.defaults());
      subject.afterCommit(1L);
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        clock.advance(TIMEOUT);
        subject.recoverExpired();
      } finally {
        release.countDown();
      }
      pool.submit(() -> {}).get(3, TimeUnit.SECONDS); // Wait until the original worker exits.
      assertThat(store.state(1).failureCode())
          .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
      assertThat(store.result(1)).isNull();
    }
  }

  @Test
  void shutdownCancelsQueuedWorkAndDoesNotExecuteAi() {
    runner.afterCommit(1L);
    runner.cancelLocalWork();
    executor.runNext();
    assertThat(calls.get()).isZero();
    assertThat(store.state(1).failureCode())
        .isEqualTo(RecommendationFailureCode.WORKER_INTERRUPTED);
  }
}
