package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationAreaTest {
  @Test
  void highIdCanOutrankEarlyProductsAndMinorityBusinessSurvivesShortlisting() {
    var data = new CandidateFixtures();
    data.products.clear();
    for (long id = 1; id <= 250; id++) data.products.put(id, at(id, 37.32117, 127.11011));
    data.products.put(
        251L, new Product(251L, 251L, "꽃", null, "꽃집", 1000, Set.of(), 37.32117, 127.11011));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(50, 12, 8, 1500));
    var preferences = new CandidateGenerator.Preferences(11L, Set.of(), Map.of(250L, 1.0));
    var result = generator.generate(data.purchase, data.products.keySet(), preferences, NOW);
    assertThat(result).hasSize(12);
    assertThat(result.getFirst().products()).anyMatch(p -> p.id().equals("250"));
    assertThat(result.stream().flatMap(c -> c.products().stream()))
        .anyMatch(p -> p.id().equals("251"));
    var reverse = new ArrayList<>(data.products.keySet());
    Collections.reverse(reverse);
    assertThat(generator.generate(data.purchase, reverse, preferences, NOW)).isEqualTo(result);
  }

  @Test
  void approvedAnchorsAndExactRadiusWithoutRounding() {
    assertThat(RecommendationArea.locate(37.3211736204431, 127.110115610261))
        .isEqualTo(RecommendationArea.BOJEONG);
    for (var point :
        List.of(
            new double[] {37.2758, 127.1497},
            new double[] {37.2779978497857, 127.15160126343},
            new double[] {37.2745843001736, 127.150512004189})) {
      assertThat(RecommendationArea.locate(point[0], point[1]))
          .isEqualTo(RecommendationArea.DONGBAEK);
    }
    double lat = 37.3211736204431;
    assertThat(RecommendationArea.locate(lat + Math.toDegrees(499.99 / 6371000), 127.110115610261))
        .isEqualTo(RecommendationArea.BOJEONG);
    assertThat(RecommendationArea.locate(lat + Math.toDegrees(500.01 / 6371000), 127.110115610261))
        .isNull();
    assertThat(RecommendationArea.locate(null, 127.1)).isNull();
    assertThat(RecommendationArea.locate(Double.NaN, 127.1)).isNull();
    assertThat(RecommendationArea.locate(37.3, 127.13)).isNull();
  }

  static Product at(long id, Double lat, Double lon) {
    return new Product(id, id, "상품 " + id, null, "카페", 1000, Set.of(11L), lat, lon);
  }

  @Test
  void scansPastTwoHundredAndNeverMixesAreasOrAcceptsUnknownCoordinates() {
    var data = new CandidateFixtures();
    data.products.clear();
    for (long id = 1; id <= 205; id++) data.products.put(id, at(id, 37.32117, 127.11011));
    data.products.put(206L, at(206, 37.2758, 127.1497));
    data.products.put(207L, at(207, 37.27799, 127.15160));
    data.products.put(208L, at(208, 37.3, 127.13));
    data.products.put(209L, at(209, null, null));
    var checked = new HashSet<Long>();
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> {
              checked.add(id);
              return id <= 200
                  ? ProductEligibility.Result.unavailable("OUTSIDE_PICKUP_HOURS")
                  : ProductEligibility.Result.available();
            },
            new CandidateGenerator.Limits(40, 12, 8, 1500));
    var result =
        generator.generate(
            data.purchase,
            data.products.keySet(),
            new CandidateGenerator.Preferences(11L, Set.of(), Map.of(205L, 1.0)),
            NOW);
    assertThat(data.catalogReads).isEqualTo(6);
    assertThat(checked).hasSize(207).doesNotContain(208L, 209L);
    assertThat(result).isNotEmpty();
    var areas = new HashSet<RecommendationArea>();
    for (var pair : result) {
      var first =
          RecommendationArea.of(data.products.get(Long.valueOf(pair.products().getFirst().id())));
      var second =
          RecommendationArea.of(data.products.get(Long.valueOf(pair.products().getLast().id())));
      assertThat(first).isNotNull().isEqualTo(second);
      areas.add(first);
    }
    assertThat(areas)
        .containsExactlyInAnyOrder(RecommendationArea.BOJEONG, RecommendationArea.DONGBAEK);
    assertThat(result.getFirst().products()).anyMatch(p -> p.id().equals("205"));
  }

  @Test
  void savedMixedOrMovedCandidatesBecomeUnavailable() {
    var data = new CandidateFixtures();
    data.products.put(2L, at(2, 37.2758, 127.1497));
    var validator =
        new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available());
    var mixed = validator.check(data.purchase, data.candidates, NOW);
    assertThat(mixed.getFirst().available()).isFalse();
    assertThat(mixed.getFirst().reasons())
        .extracting(UnavailableReason::code)
        .contains("DIFFERENT_RECOMMENDATION_AREAS");
    assertThat(mixed.getLast().available()).isTrue();
    data.products.put(2L, at(2, null, null));
    assertThat(validator.check(data.purchase, data.candidates, NOW).getFirst().reasons())
        .extracting(UnavailableReason::code)
        .contains("OUTSIDE_RECOMMENDATION_AREA");
  }
}
