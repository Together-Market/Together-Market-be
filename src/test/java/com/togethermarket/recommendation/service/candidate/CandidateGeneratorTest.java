package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.HybridRecommendationService;
import com.togethermarket.recommendation.service.RankingModel;
import com.togethermarket.recommendation.service.RecommendationInput;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import com.togethermarket.recommendation.service.candidate.CandidateGenerator.*;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CandidateGeneratorTest {
  final CandidateFixtures data = new CandidateFixtures();
  final Preferences preferences = new Preferences(11L, Set.of(12L), Map.of());
  final Limits limits = new Limits(200, 12, 8, 1500);
  final CandidateGenerator generator =
      new CandidateGenerator(
          data, (id, pickup, now) -> ProductEligibility.Result.available(), limits);

  @Test
  void onlyDistinctStorePairsWithinBudgetReachAiWithServerPrices() {
    data.products.put(4L, product(4, 3, 9000));
    data.products.put(5L, product(5, 4, Long.MAX_VALUE));
    var result = generator.generate(data.purchase, data.products.keySet(), preferences, NOW);
    assertThat(result)
        .extracting(RecommendationInput.Candidate::candidate_id)
        .containsExactly("p_1_2", "p_1_3");
    assertThat(result).allMatch(c -> c.pickup_eligible() && c.totalAmount() <= 10000);
    assertThat(result.getFirst().products().getFirst().price_amount()).isEqualTo(3000);
    assertThat(result.getFirst().products().getFirst().business_type()).isEqualTo("디저트");
    assertThat(result.getFirst().products().getLast().business_type()).isEqualTo("디저트");
  }

  @Test
  void commonEligibilityIsCalledOncePerProductWithExactContext() {
    var calls = new AtomicInteger();
    var subject =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> {
              calls.incrementAndGet();
              assertThat(pickup).isEqualTo(PICKUP);
              assertThat(now).isEqualTo(NOW);
              return id == 2L
                  ? ProductEligibility.Result.unavailable("SOLD_OUT")
                  : ProductEligibility.Result.available();
            },
            limits);
    var result = subject.generate(data.purchase, List.of(3L, 2L, 1L, 1L), preferences, NOW);
    assertThat(result)
        .extracting(RecommendationInput.Candidate::candidate_id)
        .containsExactly("p_1_3");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void generationIsDeterministicAndBoundedRegardlessOfInputOrder() {
    data.products.clear();
    for (long i = 1; i <= 10; i++) data.products.put(i, product(i, i, 1000));
    var ids = new ArrayList<>(data.products.keySet());
    var first = generator.generate(data.purchase, ids, preferences, NOW);
    Collections.reverse(ids);
    assertThat(generator.generate(data.purchase, ids, preferences, NOW))
        .isEqualTo(first)
        .hasSize(12);
    assertThat(first)
        .extracting(RecommendationInput.Candidate::candidate_id)
        .doesNotHaveDuplicates();
  }

  @Test
  void diversitySlotsPreferUnseenStoreAndBusinessPairs() {
    data.products.clear();
    data.products.put(1L, product(1, 1, 1000));
    data.products.put(2L, product(2, 2, 1000));
    data.products.put(3L, product(3, 2, 1000));
    data.products.put(4L, new Product(4L, 3L, "꽃", null, "꽃집", 1000, Set.of(), null, null));
    var subject =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new Limits(10, 2, 1, 1500));
    var result = subject.generate(data.purchase, data.products.keySet(), preferences, NOW);
    assertThat(result)
        .extracting(RecommendationInput.Candidate::candidate_id)
        .containsExactly("p_1_2", "p_1_4");
    assertThat(result.getLast().distance_meters()).isNull();
  }

  @Test
  void purposeTagsAndTrustedPreferenceAffectPreselectionWithoutInventingMetadata() {
    data.products.clear();
    data.products.put(1L, new Product(1L, 1L, "A", null, "꽃", 1000, Set.of(), null, null));
    data.products.put(2L, new Product(2L, 2L, "B", null, "꽃", 1000, Set.of(12L), null, null));
    data.products.put(3L, new Product(3L, 3L, "C", null, "꽃", 1000, Set.of(11L), null, null));
    var subject =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new Limits(10, 1, 1, 1500));
    assertThat(
            subject
                .generate(data.purchase, data.products.keySet(), preferences, NOW)
                .getFirst()
                .candidate_id())
        .isEqualTo("p_2_3");
    var preference = new Preferences(11L, Set.of(12L), Map.of(1L, 1.0));
    var result =
        subject.generate(data.purchase, data.products.keySet(), preference, NOW).getFirst();
    assertThat(result.candidate_id()).isEqualTo("p_1_3");
    assertThat(result.products().getFirst().description()).isEmpty();
    assertThat(result.distance_meters()).isNull();
  }

  @Test
  void expiredPickupEmptyCatalogAndLimitsAreHandledBeforeIo() {
    var expired = new Purchase(1L, 7L, 10000, NOW.atOffset(ZoneOffset.UTC), "RECOMMENDING");
    assertThat(generator.generate(expired, data.products.keySet(), preferences, NOW)).isEmpty();
    assertThat(generator.generate(data.purchase, List.of(), preferences, NOW)).isEmpty();
    var small =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new Limits(2, 1, 1, 1500));
    assertThatThrownBy(
            () -> small.generate(data.purchase, data.products.keySet(), preferences, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(data.catalogReads).isZero();
  }

  @Test
  void missingCatalogAndInvalidPreferenceInputsAreRejected() {
    assertThatThrownBy(() -> generator.generate(data.purchase, List.of(1L, 999L), preferences, NOW))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Preferences(11L, Set.of(), Map.of(1L, Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Preferences(11L, Set.of(), Map.of(1L, 1.1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(100, 13, 8, 1500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatedInputsFlowThroughExistingRankerAndCanBeRevalidatedAfterPriceChange() {
    var inputs = generator.generate(data.purchase, data.products.keySet(), preferences, NOW);
    var ranker =
        new HybridRecommendationService(
            (context, candidates) ->
                candidates.stream()
                    .map(
                        c ->
                            new RecommendationInput.Evaluation(
                                c.candidate_id(),
                                4,
                                4,
                                4,
                                4,
                                "ALLOWED",
                                c.products().stream().map(RecommendationInput.Product::id).toList(),
                                "상품 조합 추천"))
                    .toList(),
            RankingModel.baseline());
    var ranked =
        ranker.recommend(
            new RecommendationInput.Context("선물", 10000, PICKUP.toString(), Map.of()), inputs);
    assertThat(ranked).hasSize(2);
    var chosen = ranked.getFirst();
    var items = chosen.candidate().products();
    var saved =
        new SavedCandidate(
            100L,
            1L,
            1,
            chosen.reason(),
            List.of(
                new SavedItem(
                    200L, Long.valueOf(items.getFirst().id()), 1, items.getFirst().price_amount()),
                new SavedItem(
                    201L, Long.valueOf(items.getLast().id()), 1, items.getLast().price_amount())));
    data.products.put(1L, product(1, 1, 9000));
    var checked =
        new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available())
            .check(data.purchase, List.of(saved), NOW.plusSeconds(1))
            .getFirst();
    assertThat(checked.available()).isFalse();
    assertThat(checked.priceChanged()).isTrue();
    assertThat(checked.reasons()).extracting(UnavailableReason::code).contains("BUDGET_EXCEEDED");
    assertThat(checked.saved().reason()).isEqualTo(chosen.reason());
  }
}
