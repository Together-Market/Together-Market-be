package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateRevalidatorTest {
  final CandidateFixtures data = new CandidateFixtures();
  final CandidateRevalidator validator =
      new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available());

  @Test
  void priceChangeWithinBudgetKeepsCandidateAndHistoricalPrice() {
    data.products.put(1L, product(1, 1, 5000));
    var result = validator.check(data.purchase, data.candidates, NOW);
    assertThat(result)
        .allMatch(CheckedCandidate::available)
        .allMatch(CheckedCandidate::priceChanged);
    assertThat(result.getFirst().totalAmount()).isEqualTo(9000);
    assertThat(result.getFirst().items().getFirst().saved().unitPriceSnapshot()).isEqualTo(3000);
    assertThat(result.getLast().totalAmount()).isEqualTo(10000);
  }

  @Test
  void currentBudgetAndCommonReasonsMakeCandidateUnavailable() {
    data.products.put(1L, product(1, 1, 6001));
    var checked =
        new CandidateRevalidator(
                data,
                (id, pickup, now) ->
                    id == 2L
                        ? ProductEligibility.Result.unavailable("PAUSED")
                        : ProductEligibility.Result.available())
            .check(data.purchase, data.candidates, NOW);
    assertThat(checked).noneMatch(CheckedCandidate::available);
    assertThat(checked.getFirst().reasons())
        .containsExactly(
            new UnavailableReason(2L, "PAUSED"), new UnavailableReason(null, "BUDGET_EXCEEDED"));
  }

  @Test
  void validatesSharedProductsOnceAtTheSamePickupAndInstant() {
    var calls = new HashMap<Long, Integer>();
    new CandidateRevalidator(
            data,
            (id, pickup, now) -> {
              assertThat(pickup).isEqualTo(PICKUP);
              assertThat(now).isEqualTo(NOW);
              calls.merge(id, 1, Integer::sum);
              return ProductEligibility.Result.available();
            })
        .check(data.purchase, data.candidates, NOW);
    assertThat(calls).isEqualTo(java.util.Map.of(1L, 1, 2L, 1, 3L, 1));
    assertThat(data.catalogReads).isEqualTo(1);
  }

  @Test
  void sameStoreAndPickupBoundaryAreRejected() {
    data.products.put(2L, product(2, 1, 4000));
    var purchase = new Purchase(1L, 7L, 10000, NOW.atOffset(ZoneOffset.ofHours(9)), "READY");
    var result = validator.check(purchase, List.of(data.candidates.getFirst()), NOW).getFirst();
    assertThat(result.reasons())
        .extracting(UnavailableReason::code)
        .containsExactly("DISTINCT_STORES_REQUIRED", "PICKUP_TIME_PASSED");
  }

  @Test
  void incompleteCatalogCannotBecomeAFakeAvailableSnapshot() {
    data.products.remove(2L);
    assertThatThrownBy(() -> validator.check(data.purchase, data.candidates, NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void mixedRequestAndDuplicateIdsFailBeforeCatalogRead() {
    var first = data.candidates.getFirst();
    var other = new SavedCandidate(9L, 2L, 1, "이유", first.items());
    assertThatThrownBy(() -> validator.check(data.purchase, List.of(first, other), NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> validator.check(data.purchase, List.of(first, first), NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void emptyCandidatesNeedNoCatalogOrEligibilityCall() {
    assertThat(validator.check(data.purchase, List.of(), NOW)).isEmpty();
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void quantityIsIncludedAndOverflowFailsClosed() {
    var saved =
        new SavedCandidate(
            1L,
            1L,
            1,
            "이유",
            List.of(new SavedItem(1L, 1L, 3, 3000), new SavedItem(2L, 2L, 1, 4000)));
    var result = validator.check(data.purchase, List.of(saved), NOW).getFirst();
    assertThat(result.totalAmount()).isEqualTo(13000);
    assertThat(result.available()).isFalse();
    data.products.put(1L, product(1, 1, Long.MAX_VALUE));
    assertThatThrownBy(() -> validator.check(data.purchase, List.of(saved), NOW))
        .isInstanceOf(ArithmeticException.class);
  }
}
