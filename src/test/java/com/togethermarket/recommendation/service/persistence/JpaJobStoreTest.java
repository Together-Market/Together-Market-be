package com.togethermarket.recommendation.service.persistence;

import static com.togethermarket.recommendation.service.persistence.PersistenceAdaptersTest.identified;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.job.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaJobStoreTest {
  static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");
  final PurchaseRequestRepository purchases = mock(PurchaseRequestRepository.class);
  final RecommendationCandidateRepository candidates =
      mock(RecommendationCandidateRepository.class);
  final RecommendationItemRepository items = mock(RecommendationItemRepository.class);
  final Clock clock = mock(Clock.class);
  final JpaRecommendationJobStore store =
      new JpaRecommendationJobStore(
          purchases,
          candidates,
          items,
          clock,
          mock(com.togethermarket.product.repository.ProductRepository.class),
          mock(com.togethermarket.product.service.ProductAvailabilityService.class));
  final PurchaseRequest row =
      identified(
          new PurchaseRequest(
              7L,
              11L,
              BigDecimal.valueOf(10000),
              LocalDateTime.of(2026, 11, 14, 15, 0),
              RecommendationStatus.RECOMMENDING,
              null,
              KoreanDateTime.toDatabase(NOW),
              "[]"),
          1);

  JpaJobStoreTest() {
    when(clock.instant()).thenReturn(NOW);
    when(purchases.findLockedById(1L)).thenReturn(Optional.of(row));
  }

  @Test
  void claimPersistsTokenAndKstDeadlineAndRejectsSecondClaim() {
    var token = UUID.randomUUID();
    var lease = store.claim(1L, token, NOW, Duration.ofSeconds(40)).orElseThrow();
    assertThat(row.getRecommendationClaimToken()).isEqualTo(token.toString());
    assertThat(row.getRecommendationDeadlineAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 13, 18, 0, 40));
    assertThat(lease.deadline()).isEqualTo(NOW.plusSeconds(40));
    assertThat(store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40))).isEmpty();
  }

  @Test
  void emptyCompletionIsReadyAndTerminalResultCannotBeOverwritten() {
    var lease = store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40)).orElseThrow();
    assertThat(store.complete(lease, List.of(), NOW)).isTrue();
    assertThat(row.getStatus()).isEqualTo(RecommendationStatus.READY);
    assertThat(store.fail(lease, RecommendationFailureCode.AI_UNAVAILABLE, NOW)).isFalse();
    assertThat(store.complete(lease, List.of(), NOW)).isFalse();
  }

  @Test
  void actualTimeAfterLockWinsOverStaleCallerTimeAndLeaseDeadline() {
    var lease = store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40)).orElseThrow();
    when(purchases.findLockedById(1L))
        .thenAnswer(
            invocation -> {
              when(clock.instant()).thenReturn(NOW.plusSeconds(40));
              return Optional.of(row);
            });
    var forgedDeadline =
        new RecommendationJobStore.Lease(lease.requestId(), lease.token(), NOW.plusSeconds(1000));
    assertThat(store.complete(forgedDeadline, List.of(), NOW)).isFalse();
    assertThat(row.getFailureCode()).isEqualTo("RECOMMENDATION_TIMEOUT");
    verifyNoInteractions(items);
  }

  @Test
  void wrongTokenDoesNotMutateOrSaveResults() {
    store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40));
    var wrong = new RecommendationJobStore.Lease(1L, UUID.randomUUID(), NOW.plusSeconds(40));
    assertThat(store.complete(wrong, List.of(), NOW)).isFalse();
    assertThat(store.fail(wrong, RecommendationFailureCode.AI_TIMEOUT, NOW)).isFalse();
    assertThat(row.getStatus()).isEqualTo(RecommendationStatus.RECOMMENDING);
    verifyNoInteractions(items, candidates);
  }

  @Test
  void expiredUnclaimedRequestDoesNotStartNewWork() {
    when(clock.instant()).thenReturn(NOW.plusSeconds(40));
    assertThat(store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40))).isEmpty();
    assertThat(row.getStatus()).isEqualTo(RecommendationStatus.FAILED);
    assertThat(row.getRecommendationClaimToken()).isNull();
  }

  @Test
  void expiryRechecksRowsChangedSinceBatchSelection() {
    var lease = store.claim(1L, UUID.randomUUID(), NOW, Duration.ofSeconds(40)).orElseThrow();
    store.complete(lease, List.of(), NOW);
    when(clock.instant()).thenReturn(NOW.plusSeconds(50));
    when(purchases.findExpiredIds(any(), any(), any(), any())).thenReturn(List.of(1L));
    assertThat(store.expire(NOW, Duration.ofSeconds(40), 100)).isZero();
    assertThat(row.getStatus()).isEqualTo(RecommendationStatus.READY);
  }
}
