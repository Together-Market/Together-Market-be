package com.togethermarket.recommendation.service.job;

import static com.togethermarket.recommendation.service.job.JobFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

class RecommendationJobRuntimeTest {
  @Test
  void boundedPoolRejectsExcessWorkAndRunsAcceptedJobsAsynchronously() throws Exception {
    var store = new MemoryStore();
    store.add(1);
    store.add(2);
    store.add(3);
    var clock = new MutableClock();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var committed = new CountDownLatch(2);
    RecommendationJobStore observed =
        mock(RecommendationJobStore.class, AdditionalAnswers.delegatesTo(store));
    doAnswer(
            call -> {
              boolean result =
                  store.complete(call.getArgument(0), call.getArgument(1), call.getArgument(2));
              committed.countDown();
              return result;
            })
        .when(observed)
        .complete(any(), anyList(), any());
    try (var runtime =
        new RecommendationJobRuntime(
            observed,
            id -> {
              if (id == 1L) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS))
                  throw new IllegalStateException("Test timed out");
              }
              return List.of();
            },
            clock,
            RecommendationJobRunner.Policy.defaults(),
            1,
            1,
            Duration.ofSeconds(1))) {
      try {
        assertThat(runtime.afterCommit(1L))
            .isEqualTo(RecommendationJobRunner.DispatchResult.SUBMITTED);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.afterCommit(2L))
            .isEqualTo(RecommendationJobRunner.DispatchResult.SUBMITTED);
        assertThat(runtime.afterCommit(3L))
            .isEqualTo(RecommendationJobRunner.DispatchResult.REJECTED);
        assertThat(store.state(3).failureCode())
            .isEqualTo(RecommendationFailureCode.DISPATCH_REJECTED);
      } finally {
        release.countDown();
      }
      assertThat(committed.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(store.state(1).status()).isEqualTo("READY");
      assertThat(store.state(2).status()).isEqualTo("READY");
    }
  }

  @Test
  void recoveryRunsAtStartupAndContinuesAfterTransientStoreFailure() throws Exception {
    var store = new MemoryStore();
    store.add(1);
    var clock = new MutableClock();
    clock.advance(TIMEOUT);
    var attempts = new AtomicInteger();
    var recovered = new CountDownLatch(1);
    RecommendationJobStore observed =
        mock(RecommendationJobStore.class, AdditionalAnswers.delegatesTo(store));
    doAnswer(
            call -> {
              if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("Transient database outage");
              int count =
                  store.expire(call.getArgument(0), call.getArgument(1), call.getArgument(2));
              recovered.countDown();
              return count;
            })
        .when(observed)
        .expire(any(Instant.class), any(Duration.class), anyInt());
    try (var runtime =
        new RecommendationJobRuntime(
            observed,
            id -> {
              throw new AssertionError("No AI during recovery");
            },
            clock,
            RecommendationJobRunner.Policy.defaults(),
            1,
            1,
            Duration.ofMillis(10))) {
      assertThat(recovered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
      assertThat(store.state(1).failureCode())
          .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
    }
  }
}
