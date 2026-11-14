package com.togethermarket.recommendation.service.job;

import static com.togethermarket.recommendation.service.job.JobFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationJobStateTest {
  final UUID token = UUID.randomUUID();
  final RecommendationJobState pending = RecommendationJobState.pending(1L, START);

  @Test
  void claimUsesCreationDeadlineAndCannotBeTakenTwice() {
    var claimed = pending.claim(token, START.plusSeconds(20), TIMEOUT);
    assertThat(claimed.deadline()).isEqualTo(START.plusSeconds(40));
    assertThat(claimed.claim(UUID.randomUUID(), START.plusSeconds(21), TIMEOUT)).isEqualTo(claimed);
    assertThat(claimed.owns(token)).isTrue();
  }

  @Test
  void successBeforeDeadlineAndExpiryAtExactDeadlineAreDistinct() {
    var claimed = pending.claim(token, START, TIMEOUT);
    assertThat(claimed.complete(token, START.plusSeconds(40).minusNanos(1)).status())
        .isEqualTo("READY");
    var expired = claimed.complete(token, START.plusSeconds(40));
    assertThat(expired.status()).isEqualTo("FAILED");
    assertThat(expired.failureCode()).isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
  }

  @Test
  void lostDispatchAndDuplicateLateDispatchExpireWithoutComputing() {
    var expired = pending.expire(START.plusSeconds(40), TIMEOUT);
    assertThat(expired.status()).isEqualTo("FAILED");
    assertThat(expired.claimToken()).isNull();
    assertThat(pending.claim(token, START.plusSeconds(40), TIMEOUT)).isEqualTo(expired);
  }

  @Test
  void terminalStatesCannotBeOverwrittenByLateSuccessFailureOrRecovery() {
    var claimed = pending.claim(token, START, TIMEOUT);
    var ready = claimed.complete(token, START.plusSeconds(1));
    var failed =
        claimed.fail(token, RecommendationFailureCode.AI_UNAVAILABLE, START.plusSeconds(1));
    for (var terminal : java.util.List.of(ready, failed)) {
      assertThat(terminal.complete(token, START.plusSeconds(2))).isEqualTo(terminal);
      assertThat(terminal.fail(token, RecommendationFailureCode.AI_TIMEOUT, START.plusSeconds(2)))
          .isEqualTo(terminal);
      assertThat(terminal.expire(START.plusSeconds(100), TIMEOUT)).isEqualTo(terminal);
      assertThat(terminal.claim(UUID.randomUUID(), START.plusSeconds(100), TIMEOUT))
          .isEqualTo(terminal);
    }
  }

  @Test
  void onlyOwnerTokenMayCompleteOrFailAndTimeoutWinsAtBoundary() {
    var claimed = pending.claim(token, START, TIMEOUT);
    assertThat(claimed.complete(UUID.randomUUID(), START.plusSeconds(1))).isEqualTo(claimed);
    assertThat(
            claimed.fail(
                UUID.randomUUID(), RecommendationFailureCode.AI_UNAVAILABLE, START.plusSeconds(1)))
        .isEqualTo(claimed);
    assertThat(
            claimed
                .fail(token, RecommendationFailureCode.AI_UNAVAILABLE, START.plusSeconds(40))
                .failureCode())
        .isEqualTo(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
  }

  @Test
  void invalidPersistedStatesAndPolicyAreRejected() {
    assertThatThrownBy(() -> new RecommendationJobState(1L, START, "FAILED", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecommendationJobState(
                    1L, START, "RECOMMENDING", null, null, RecommendationFailureCode.AI_TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pending.claim(token, START.minusSeconds(1), TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pending.claim(token, START, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
