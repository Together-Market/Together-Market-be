package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

class StorePairDistanceTest {
  private static final double LAT = 37.3211736204431;
  private static final double LON = 127.110115610261;

  private static Product offset(long id, double meters) {
    return new Product(
        id,
        id,
        "상품 " + id,
        null,
        "카페",
        1000,
        Set.of(11L),
        LAT + Math.toDegrees(meters / 6371000),
        LON);
  }

  @Test
  void inclusiveLimitRejectsJustOutsideWithoutRoundingAndUnknownCoordinates() {
    assertThat(StorePairDistance.allows(500)).isTrue();
    assertThat(StorePairDistance.allows(500.01)).isFalse();
    assertThat(StorePairDistance.allows(Double.NaN)).isFalse();
    assertThat(StorePairDistance.allows(Double.POSITIVE_INFINITY)).isFalse();
    assertThat(StorePairDistance.allows(-1)).isFalse();
    assertThat(StorePairDistance.allows(LAT, LON, null, LON)).isFalse();
    assertThat(StorePairDistance.allows(LAT, LON, Double.NaN, LON)).isFalse();
    assertThat(StorePairDistance.allows(LAT, LON, 91.0, LON)).isFalse();
    var a = offset(1, -250);
    for (double meters : List.of(249.99, 250.01)) {
      var b = offset(2, meters);
      assertThat(StorePairDistance.allows(a.latitude(), a.longitude(), b.latitude(), b.longitude()))
          .isEqualTo(meters < 250);
    }
  }

  @Test
  void sameAreaDoesNotAllowDistantPairsAndBoundaryIsCheckedBeforeAi() {
    var data = new CandidateFixtures();
    data.products.clear();
    data.products.put(1L, offset(1, -250));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(500, 12, 8, 1500));
    for (double meters : List.of(249.99, 250.01, 400.0)) {
      data.products.put(2L, offset(2, meters));
      assertThat(data.products.values())
          .allMatch(p -> RecommendationArea.of(p) == RecommendationArea.BOJEONG);
      var result =
          generator.generate(
              data.purchase,
              data.products.keySet(),
              new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
              NOW);
      assertThat(result).hasSize(meters < 250 ? 1 : 0);
    }
  }

  @Test
  void movedStoresBecomeUnavailableAndAreExcludedFromAlternatives() {
    var data = new CandidateFixtures();
    data.products.put(1L, offset(1, -250));
    data.products.put(2L, offset(2, 200));
    data.products.put(3L, offset(3, 249.99));
    var validator =
        new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available());
    var reads =
        new RecommendationReadService(data, validator, Clock.fixed(NOW, ZoneOffset.UTC), 100);
    assertThat(reads.alternatives("5", 7L, PICKUP, 1, 20).items()).hasSize(1);
    data.products.put(3L, offset(3, 250.01));
    var page = reads.recommendations("1", 7L, 1, 20);
    assertThat(page.totalCount()).isEqualTo(2);
    assertThat(page.availableCount()).isEqualTo(1);
    assertThat(reads.detail("2", 7L).reasons())
        .extracting(UnavailableReason::code)
        .contains("STORE_DISTANCE_EXCEEDED_OR_UNKNOWN");
    assertThat(reads.alternatives("5", 7L, PICKUP, 1, 20).items()).isEmpty();
  }
}
