package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationReadServiceTest {
  final CandidateFixtures data = new CandidateFixtures();
  final RecommendationReadService service =
      service((id, pickup, now) -> ProductEligibility.Result.available());

  RecommendationReadService service(ProductEligibility eligibility) {
    return new RecommendationReadService(
        data, new CandidateRevalidator(data, eligibility), Clock.fixed(NOW, ZoneOffset.UTC), 100);
  }

  @Test
  void sessionIsRequiredBeforeAnyLookup() {
    assertCode(() -> service.recommendations("1", null, null, null), "UNAUTHORIZED");
    assertCode(() -> service.detail("1", null), "UNAUTHORIZED");
    assertCode(() -> service.alternatives("5", null, PICKUP, null, null), "UNAUTHORIZED");
    assertThat(data.sourceReads).isZero();
  }

  @Test
  void ownershipIsCheckedBeforeCatalogAndCommonValidation() {
    assertCode(() -> service.recommendations("1", 8L, null, null), "FORBIDDEN");
    assertCode(() -> service.detail("1", 8L), "FORBIDDEN");
    assertCode(() -> service.alternatives("5", 8L, PICKUP, null, null), "FORBIDDEN");
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void listKeepsUnavailableCandidatesAndUsesRankThenNumericId() {
    data.candidates = List.of(saved(10, 1, 1, 2), saved(2, 1, 1, 3), saved(1, 2, 1, 2));
    data.products.put(3L, product(3, 2, 8000));
    var result = service.recommendations("1", 7L, 1, 2);
    assertThat(result.items()).extracting(c -> c.saved().id()).containsExactly(2L, 10L);
    assertThat(result.items().getFirst().available()).isFalse();
    assertThat(result.totalCount()).isEqualTo(3);
    assertThat(result.availableCount()).isEqualTo(2);
    assertThat(result.message()).isNull();
  }

  @Test
  void emptyPageDoesNotClaimThereAreNoRecommendations() {
    var result = service.recommendations("1", 7L, Integer.MAX_VALUE, 100);
    assertThat(result.items()).isEmpty();
    assertThat(result.totalCount()).isEqualTo(2);
    assertThat(result.message()).isNull();
    var defaults = service.recommendations("1", 7L, null, null);
    assertThat(defaults.page()).isEqualTo(1);
    assertThat(defaults.size()).isEqualTo(20);
  }

  @Test
  void noCandidatesAndNoAvailableCandidatesHaveDistinctResults() {
    var unavailable =
        service((id, pickup, now) -> ProductEligibility.Result.unavailable("SOLD_OUT"))
            .recommendations("1", 7L, null, null);
    assertThat(unavailable.items()).hasSize(2);
    assertThat(unavailable.availableCount()).isZero();
    assertThat(unavailable.message()).isNotBlank();
    data.candidates = List.of();
    var empty = service.recommendations("1", 7L, null, null);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.totalCount()).isZero();
    assertThat(empty.message()).isNotBlank().isNotEqualTo(unavailable.message());
  }

  @Test
  void alternativesExcludeOriginalPairButAllowOtherProductsFromSameStore() {
    data.products.put(4L, product(4, 1, 2000));
    data.candidates =
        List.of(
            saved(1, 1, 1, 2),
            saved(2, 2, 2, 1),
            saved(3, 3, 1, 3),
            saved(4, 4, 4, 2),
            saved(5, 5, 2, 4));
    var pickup = PICKUP.plusHours(1);
    var subject =
        service(
            (id, requestedPickup, evaluatedAt) -> {
              assertThat(requestedPickup).isEqualTo(pickup);
              assertThat(evaluatedAt).isEqualTo(NOW);
              return id == 3L
                  ? ProductEligibility.Result.unavailable("PREPARATION_TIME")
                  : ProductEligibility.Result.available();
            });
    var result = subject.alternatives("5", 7L, pickup, 1, 1);
    assertThat(result.items()).extracting(c -> c.saved().id()).containsExactly(4L);
    assertThat(result.items().getFirst().saved().rank()).isEqualTo(4);
    assertThat(result.totalCount()).isEqualTo(1);
    assertThat(result.availableCount()).isEqualTo(1);
    var beyond = subject.alternatives("5", 7L, pickup, 2, 1);
    assertThat(beyond.items()).isEmpty();
    assertThat(beyond.totalCount()).isEqualTo(1);
    assertThat(beyond.message()).isNull();
  }

  @Test
  void alternativesRequireFailedReservationAndMatchingPurchaseOwner() {
    data.reservation = new Reservation(5L, 7L, 1L, 1L, false);
    assertCode(() -> service.alternatives("5", 7L, PICKUP, null, null), "RESERVATION_NOT_FAILED");
    data.reservation = new Reservation(5L, 7L, 1L, 1L, true);
    data.purchase = new Purchase(1L, 8L, 10000, PICKUP, "READY");
    assertCode(() -> service.alternatives("5", 7L, PICKUP, null, null), "FORBIDDEN");
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void alternativesNeverChooseAnImplicitPickupPolicy() {
    assertThatThrownBy(() -> service.alternatives("5", 7L, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(data.catalogReads).isZero();
    var result = service.alternatives("5", 7L, NOW.atOffset(ZoneOffset.UTC), null, null);
    assertThat(result.items()).isEmpty();
    assertThat(result.totalCount()).isZero();
    assertThat(result.message()).isNotBlank();
  }

  @Test
  void wrongSourceRowsAndBrokenOriginalLinkFailBeforeCatalog() {
    data.candidates =
        List.of(new SavedCandidate(1L, 2L, 1, "이유", data.candidates.getFirst().items()));
    assertThatThrownBy(() -> service.recommendations("1", 7L, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.alternatives("5", 7L, PICKUP, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void invalidPaginationAndUnreadyRequestsAreRejected() {
    assertCode(() -> service.recommendations("1", 7L, 0, 20), "VALIDATION_ERROR");
    assertCode(() -> service.recommendations("1", 7L, 1, 101), "VALIDATION_ERROR");
    assertCode(() -> service.recommendations("1", 7L, 1, 0), "VALIDATION_ERROR");
    data.purchase = new Purchase(1L, 7L, 10000, PICKUP, "RECOMMENDING");
    assertCode(() -> service.recommendations("1", 7L, null, null), "RECOMMENDATION_IN_PROGRESS");
    data.purchase = new Purchase(1L, 7L, 10000, PICKUP, "FAILED");
    assertCode(() -> service.detail("1", 7L), "RECOMMENDATION_FAILED");
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void detailReturnsCurrentConditionsAndMissingResourcesStayDistinct() {
    assertThat(service.detail("1", 7L).totalAmount()).isEqualTo(7000);
    assertCode(() -> service.detail("999", 7L), "RECOMMENDATION_NOT_FOUND");
    assertCode(() -> service.recommendations("999", 7L, null, null), "PURCHASE_REQUEST_NOT_FOUND");
    assertCode(() -> service.alternatives("999", 7L, PICKUP, null, null), "RESERVATION_NOT_FOUND");
    data.reservation = new Reservation(5L, 7L, 1L, 999L, true);
    assertCode(
        () -> service.alternatives("5", 7L, PICKUP, null, null), "RECOMMENDATION_DATA_UNAVAILABLE");
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
