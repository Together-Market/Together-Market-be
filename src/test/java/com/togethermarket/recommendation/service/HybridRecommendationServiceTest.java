package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togethermarket.recommendation.service.RecommendationInput.Candidate;
import com.togethermarket.recommendation.service.RecommendationInput.Context;
import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import com.togethermarket.recommendation.service.RecommendationInput.Product;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HybridRecommendationServiceTest {
  private final Context context =
      new Context("생일", 60000, "2026-11-15T18:00:00+09:00", Map.of("style", "아기자기함"));

  private Product product(String id, String store, long price, boolean available) {
    return new Product(id, store, "시연 상품", "검수한 시연 설명", "시연 업종", price, available);
  }

  private Candidate candidate(String id, String a, String b) {
    return new Candidate(
        id,
        List.of(product(a, "s" + a, 20000, true), product(b, "s" + b, 15000, true)),
        210.,
        true);
  }

  private Evaluation evaluation(Candidate c, String status) {
    return new Evaluation(
        c.candidate_id(),
        5,
        5,
        4,
        5,
        status,
        c.products().stream().map(Product::id).toList(),
        "구매 목적에 어울리는 시연 조합입니다.");
  }

  @Test
  void filtersBudgetSalesAndPickupBeforeApiCall() {
    AtomicInteger calls = new AtomicInteger();
    var service =
        new HybridRecommendationService(
            (ctx, candidates) -> {
              calls.incrementAndGet();
              return List.of();
            },
            RankingModel.baseline());
    var expensive =
        new Candidate(
            "a",
            List.of(product("1", "s1", 50000, true), product("2", "s2", 50000, true)),
            null,
            true);
    var stopped =
        new Candidate(
            "b",
            List.of(product("3", "s3", 10000, false), product("4", "s4", 10000, true)),
            null,
            true);
    var closed =
        new Candidate(
            "c",
            List.of(product("5", "s5", 10000, true), product("6", "s6", 10000, true)),
            null,
            false);
    assertThat(service.recommend(context, List.of(expensive, stopped, closed))).isEmpty();
    assertThat(calls.get()).isZero();
  }

  @Test
  void rejectsUnknownDuplicateMissingAndUnrelatedEvidence() {
    Candidate c = candidate("a", "1", "2");
    Evaluation valid = evaluation(c, "ALLOWED");
    Evaluation unknown = evaluation(candidate("fake", "3", "4"), "ALLOWED");
    Evaluation badEvidence = new Evaluation("a", 5, 5, 5, 5, "ALLOWED", List.of("invented"), "이유");
    for (List<Evaluation> result :
        List.of(
            List.of(unknown), List.of(valid, valid), List.<Evaluation>of(), List.of(badEvidence))) {
      var service =
          new HybridRecommendationService((ctx, candidates) -> result, RankingModel.baseline());
      assertThatThrownBy(() -> service.recommend(context, List.of(c)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void uncertainAndExcludedCandidatesAreNeverShown() {
    var a = candidate("a", "1", "2");
    var b = candidate("b", "3", "4");
    var service =
        new HybridRecommendationService(
            (ctx, candidates) -> List.of(evaluation(a, "UNKNOWN"), evaluation(b, "EXCLUDED")),
            RankingModel.baseline());
    assertThat(service.recommend(context, List.of(a, b))).isEmpty();
  }

  @Test
  void diversityPenalizesRepeatedProductsAndKeepsDeterministicTies() {
    var a = candidate("a", "1", "2");
    var b = candidate("b", "1", "3");
    var c = candidate("c", "4", "5");
    var service =
        new HybridRecommendationService(
            (ctx, candidates) ->
                candidates.stream().map(item -> evaluation(item, "ALLOWED")).toList(),
            RankingModel.baseline());
    assertThat(service.recommend(context, List.of(b, c, a)))
        .extracting(r -> r.candidate().candidate_id())
        .containsExactly("a", "c", "b");
    assertThat(service.recommend(context, List.of(a)).getFirst().ranker_mode())
        .isEqualTo("baseline");
  }

  @Test
  void duplicatePairsAndSameStoreAreRejected() {
    var a = candidate("a", "1", "2");
    var reverse = new Candidate("b", List.of(a.products().get(1), a.products().get(0)), 210., true);
    var service =
        new HybridRecommendationService((ctx, candidates) -> List.of(), RankingModel.baseline());
    assertThatThrownBy(() -> service.recommend(context, List.of(a, reverse)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Candidate(
                    "bad",
                    List.of(product("1", "s", 100, true), product("2", "s", 100, true)),
                    null,
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void extractionMatchesPythonContractAndMissingDistanceIsZero() {
    var c =
        new Candidate(
            "a",
            List.of(product("1", "s1", 30000, true), product("2", "s2", 25000, true)),
            210.,
            true);
    var values = HybridRecommendationService.features(context, c, evaluation(c, "ALLOWED"));
    assertThat(RankingModel.baseline().score(values))
        .isCloseTo(91.01666666666667, org.assertj.core.data.Offset.offset(1e-10));
    var unknownDistance = new Candidate("b", c.products(), null, true);
    assertThat(
            HybridRecommendationService.features(
                    context, unknownDistance, evaluation(unknownDistance, "ALLOWED"))
                .get(4))
        .isZero();
  }

  @Test
  void sharedGoldenVectorsMatchPythonIncludingNegativeWeights() throws IOException {
    try (var stream = getClass().getResourceAsStream("/recommendation/parity-cases.json")) {
      var data =
          RecommendationJson.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      for (Object item : RecommendationJson.array(data.get("cases"))) {
        var row = RecommendationJson.object(item);
        var weights =
            RecommendationJson.array(row.get("weights")).stream()
                .map(RecommendationJson::number)
                .toList();
        var values =
            RecommendationJson.array(row.get("features")).stream()
                .map(RecommendationJson::number)
                .toList();
        assertThat(new RankingModel("trained", "test", weights).score(values))
            .isCloseTo(
                RecommendationJson.number(row.get("score")),
                org.assertj.core.data.Offset.offset(1e-10));
      }
    }
  }

  @Test
  void invalidFeaturesAndWeightsFailClosed() {
    assertThatThrownBy(() -> new RankingModel("trained", "test", List.of(0., 0., 0., 0., 0., 0.)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RankingModel.baseline().score(List.of(1., 1., 1., 1., 1., Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RankingModel.baseline().score(List.of(1., 1., 1., 1., 1., 1.1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
