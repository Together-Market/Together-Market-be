package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togethermarket.recommendation.service.RecommendationInput.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecipientRankingTest {
  private Candidate pair(String id, String firstId, String firstName, String secondName) {
    return new Candidate(
        id,
        List.of(
            new Product(firstId, "s" + firstId, firstName, "", "업종", 1000, true),
            new Product(id + "b", "s" + id + "b", secondName, "", "업종", 1000, true)),
        1000.,
        true);
  }

  private Context context(Map<String, String> answers) {
    return new Context("선물", 60000, "2026-11-16T18:00:00+09:00", answers);
  }

  private Evaluation evaluation(Candidate c, int recipient, boolean preferred) {
    int other = preferred ? 5 : 2;
    return new Evaluation(
        c.candidate_id(),
        other,
        other,
        recipient,
        other,
        "ALLOWED",
        c.products().stream().map(Product::id).toList(),
        "상품 정보에 따른 추천입니다.");
  }

  @Test
  void parentsFlowersAndCakeBeatStationeryRegardlessOfModelAndAiScores() {
    var stationery = pair("a", "a1", "노트", "펜");
    var flowers = pair("b", "b1", "꽃다발", "생크림 케이크");
    var repeat = pair("c", "b1", "꽃다발", "떡케이크");
    for (var mode : List.of("baseline", "trained", "demo")) {
      var model = new RankingModel(mode, "test", List.of(100., 100., -100., 100., 100., 100.));
      var service =
          new HybridRecommendationService(
              (ctx, candidates) ->
                  candidates.stream()
                      .map(c -> evaluation(c, c == stationery ? 5 : 0, c == stationery))
                      .toList(),
              model);
      assertThat(
              service.recommend(
                  context(Map.of("recipient", "부모님")), List.of(stationery, repeat, flowers)))
          .extracting(r -> r.candidate().candidate_id())
          .containsExactly("b", "c", "a");
    }
  }

  @Test
  void everySpecifiedRecipientOutranksOtherScoresAndDiversity() {
    var weak = pair("a", "a1", "상품 A", "상품 B");
    var strong = pair("b", "b1", "상품 C", "상품 D");
    var repeated = pair("c", "b1", "상품 C", "상품 E");
    var service =
        new HybridRecommendationService(
            (ctx, candidates) ->
                candidates.stream().map(c -> evaluation(c, c == weak ? 4 : 5, c == weak)).toList(),
            new RankingModel("trained", "test", List.of(100., 100., -100., 100., 100., 100.)));
    for (String recipient : List.of("친구", "연인", "동료", "선생님")) {
      assertThat(
              service.recommend(
                  context(Map.of("recipient", recipient)), List.of(weak, strong, repeated)))
          .extracting(r -> r.candidate().candidate_id())
          .containsExactly("b", "c", "a");
    }
    assertThat(
            service
                .recommend(context(Map.of()), List.of(weak, strong, repeated))
                .getFirst()
                .candidate()
                .candidate_id())
        .isEqualTo("a");
  }

  @Test
  void excludedParentGiftsRemainExcludedAndUnavailableGiftsNeverReachAi() {
    var flowers = pair("a", "a1", "꽃다발", "케이크");
    var other = pair("b", "b1", "노트", "펜");
    var service =
        new HybridRecommendationService(
            (ctx, candidates) ->
                candidates.stream()
                    .map(
                        c ->
                            c == flowers
                                ? new Evaluation(
                                    c.candidate_id(),
                                    5,
                                    5,
                                    5,
                                    5,
                                    "EXCLUDED",
                                    c.products().stream().map(Product::id).toList(),
                                    "제외 상품입니다.")
                                : evaluation(c, 3, true))
                    .toList(),
            RankingModel.baseline());
    assertThat(service.recommend(context(Map.of("recipient", "부모님")), List.of(flowers, other)))
        .extracting(r -> r.candidate().candidate_id())
        .containsExactly("b");
  }
}
