package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.*;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BirthdayRecommendationTest {
  private Product product(long id, String name, long price) {
    return new Product(id, id, name, "", "선물", price, Set.of(11L), 37.32117, 127.11011);
  }

  private CandidateGenerator.Preferences preferences(Map<String, String> answers) {
    return new CandidateGenerator.Preferences(
        11L,
        Set.of(),
        Map.of(),
        PreferenceSignals.from(answers),
        RecipientPriority.from(answers),
        GiftComposition.from("생신"));
  }

  @Test
  void grandmotherBirthdayRequiresOneWholeCakeAndUsesBudgetBeforeOtherScores() {
    var data = new CandidateFixtures();
    data.products =
        new HashMap<>(
            Map.of(
                1L, product(1, "생신 홀케이크 2호", 55000),
                2L, product(2, "꽃다발", 20000),
                3L, product(3, "대형 꽃바구니", 65000),
                4L, product(4, "미니 케이크", 26000),
                5L, product(5, "노트", 1000),
                6L, product(6, "홀케이크 3호", 70000)));
    var answers = Map.of("recipient", "할머니", "category_preference", "문구와 도서");
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(2, 12, 8, 1500));
    var candidates =
        generator.generate(
            new Purchase(1L, 7L, 120000, PICKUP, "RECOMMENDING"),
            data.products.keySet(),
            preferences(answers),
            NOW);
    assertThat(candidates.getFirst().totalAmount()).isEqualTo(120000);
    assertThat(candidates)
        .allSatisfy(
            c ->
                assertThat(
                        c.products().stream()
                            .filter(p -> GiftComposition.wholeCake(p.name(), p.description()))
                            .count())
                    .isEqualTo(1));
    var ranker =
        new HybridRecommendationService(
            (ctx, rows) ->
                rows.stream()
                    .map(
                        c ->
                            new RecommendationInput.Evaluation(
                                c.candidate_id(),
                                5,
                                5,
                                5,
                                5,
                                "ALLOWED",
                                c.products().stream().map(p -> p.id()).toList(),
                                "생신 선물 구성입니다."))
                    .toList(),
            new RankingModel("trained", "cheap-only", List.of(0., 0., 0., 0., 0., 100.)));
    var result =
        ranker.recommend(
            new RecommendationInput.Context("생신", 120000, PICKUP.toString(), answers), candidates);
    assertThat(result.getFirst().candidate().products())
        .extracting(p -> p.name())
        .containsExactly("생신 홀케이크 2호", "대형 꽃바구니");
    assertThat(result.getFirst().candidate().totalAmount()).isEqualTo(120000);
  }

  @Test
  void absentExcludedUnaffordableOrUnavailableWholeCakeNeverFallsBackToStationery() {
    var data = new CandidateFixtures();
    data.products =
        new HashMap<>(
            Map.of(
                1L,
                product(1, "홀케이크 1호", 35000),
                2L,
                product(2, "노트", 1000),
                3L,
                product(3, "미니 케이크", 20000)));
    var allAvailable =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(10, 12, 8, 1500));
    var purchase = new Purchase(1L, 7L, 120000, PICKUP, "RECOMMENDING");
    assertThat(allAvailable.generate(purchase, Set.of(2L, 3L), preferences(Map.of()), NOW))
        .isEmpty();
    assertThat(
            allAvailable.generate(
                new Purchase(1L, 7L, 35000, PICKUP, "RECOMMENDING"),
                data.products.keySet(),
                preferences(Map.of()),
                NOW))
        .isEmpty();
    assertThat(
            allAvailable.generate(
                purchase,
                data.products.keySet(),
                preferences(Map.of("excluded_products", "케이크")),
                NOW))
        .isEmpty();
    var unavailable =
        new CandidateGenerator(
            data,
            (id, pickup, now) ->
                id == 1
                    ? ProductEligibility.Result.unavailable("SOLD_OUT")
                    : ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(10, 12, 8, 1500));
    assertThat(unavailable.generate(purchase, data.products.keySet(), preferences(Map.of()), NOW))
        .isEmpty();
    var calls = new AtomicInteger();
    var ranker =
        new HybridRecommendationService(
            (ctx, rows) -> {
              calls.incrementAndGet();
              return List.of();
            },
            RankingModel.baseline());
    var invalid =
        new RecommendationInput.Candidate(
            "mini",
            List.of(
                new RecommendationInput.Product("2", "2", "노트", "", "문구", 1000, true),
                new RecommendationInput.Product("3", "3", "미니 케이크", "", "제과", 20000, true)),
            100.,
            true);
    assertThat(
            ranker.recommend(
                new RecommendationInput.Context("생일", 120000, PICKUP.toString(), Map.of()),
                List.of(invalid)))
        .isEmpty();
    assertThat(calls).hasValue(0);
  }

  @Test
  void onlyExplicitWholeCakesQualifyAndOtherOccasionsKeepTheirComposition() {
    for (String name : List.of("홀케이크", "생크림 케이크 2호", "홀 케익 1호", "Whole cake"))
      assertThat(GiftComposition.wholeCake(name, "")).isTrue();
    for (String name :
        List.of("미니 케이크", "조각 케이크", "컵케이크", "팬케이크", "홀케이크 토퍼", "케이크", "홀케이크 상자", "롤케이크"))
      assertThat(GiftComposition.wholeCake(name, "")).as(name).isFalse();
    assertThat(GiftComposition.wholeCake("딸기 케이크", "2호 홀케이크 1개")).isTrue();
    for (String purpose : List.of("생일", "할머니 생신", "생일파티")) {
      var policy = GiftComposition.from(purpose);
      assertThat(policy.accepts(true, false)).isTrue();
      assertThat(policy.accepts(true, true)).isFalse();
      assertThat(policy.accepts(false, false)).isFalse();
    }
    assertThat(GiftComposition.from("감사").accepts(false, false)).isTrue();
    for (String recipient : List.of("할머니", "할아버지", "외할머니", "조부모님"))
      assertThat(RecipientPriority.from(Map.of("recipient", recipient)).parents()).isTrue();
  }
}
