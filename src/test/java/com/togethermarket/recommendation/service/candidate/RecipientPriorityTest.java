package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.candidate.CandidateData.Product;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipientPriorityTest {
  @Test
  void individualKnittingInterestRequiresMaterialsBeforeGenericFamilyGifts() {
    for (var answers :
        List.of(
            Map.of("recipient", "뜨개질을 좋아하시는 할머니"),
            Map.of("recipient", "할머니", "category_preference", "뜨개질 재료와 케이크"),
            Map.of("recipient", "할머니", "style", "뜨개질을 좋아하세요"))) {
      var policy = RecipientPriority.from(answers);
      assertThat(policy.knitting()).isTrue();
      assertThat(policy.pairTier("뜨개실·코바늘 재료 세트", "홀케이크 2호"))
          .isGreaterThan(policy.pairTier("꽃다발", "홀케이크 2호"));
      assertThat(policy.accepts(policy.productKind("드립백 커피 선물 세트"), policy.productKind("문구 선물 세트")))
          .isFalse();
      assertThat(policy.accepts(policy.productKind("꽃바구니"), policy.productKind("홀케이크 2호")))
          .isFalse();
    }
    for (String name : List.of("뜨개실·코바늘 재료 세트", "코바늘", "프리미엄 뜨개실·대바늘 세트", "목도리 뜨개질 키트"))
      assertThat(RecipientPriority.knittingMaterial(name)).isTrue();
    for (String name : List.of("코바늘 인형 완제품", "뜨개 가방", "코바늘 교재", "문구 세트"))
      assertThat(RecipientPriority.knittingMaterial(name)).isFalse();
    assertThat(
            RecipientPriority.from(Map.of("recipient", "할머니", "category_preference", "뜨개질 말고 꽃"))
                .knitting())
        .isFalse();
    var excluded = PreferenceSignals.from(Map.of("excluded_products", "뜨개질"));
    assertThat(excluded.excludes("프리미엄 뜨개실·대바늘 세트")).isTrue();
  }

  private Product product(long id, String name, long price, Set<Long> categories) {
    return new Product(id, id, name, "", "업종", price, categories, 37.32117, 127.11011, "id:" + id);
  }

  @Test
  void parentsGetFlowersAndCakeBeforeHighScoringStationeryAcrossCatalogBatches() {
    var data = new CandidateFixtures();
    data.products.clear();
    var scores = new HashMap<Long, Double>();
    for (long id = 1; id <= 260; id++) {
      data.products.put(id, product(id, "문구 노트 " + id, 100, Set.of(11L)));
      scores.put(id, 1.0);
    }
    data.products.put(261L, product(261, "카네이션 꽃다발", 4000, Set.of()));
    data.products.put(262L, product(262, "생크림 케이크", 5000, Set.of()));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(20, 12, 8, 1500));
    var answers = Map.of("recipient", "부모님", "category_preference", "문구와 도서");
    var prefs =
        new CandidateGenerator.Preferences(
            11L,
            Set.of(),
            scores,
            PreferenceSignals.from(answers),
            RecipientPriority.from(answers));
    var result = generator.generate(data.purchase, data.products.keySet(), prefs, NOW);
    assertThat(result.getFirst().products()).extracting(p -> p.id()).containsExactly("261", "262");
    var policy = prefs.recipient();
    assertThat(result)
        .extracting(c -> policy.pairTier(c.products().get(0).name(), c.products().get(1).name()))
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  @Test
  void recipientPriorityStillRespectsExclusionsBudgetAndAvailability() {
    var data = new CandidateFixtures();
    data.products.clear();
    data.products.put(1L, product(1, "꽃다발", 3000, Set.of(11L)));
    data.products.put(2L, product(2, "케이크", 4000, Set.of(11L)));
    data.products.put(3L, product(3, "꽃바구니", 20000, Set.of(11L)));
    data.products.put(4L, product(4, "쿠키", 1000, Set.of(11L)));
    data.products.put(5L, product(5, "노트", 1000, Set.of(11L)));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) ->
                id == 2
                    ? ProductEligibility.Result.unavailable("CLOSED")
                    : ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(20, 12, 8, 1500));
    var answers = Map.of("recipient", "부모님", "excluded_products", "꽃");
    var result =
        generator.generate(
            data.purchase,
            data.products.keySet(),
            new CandidateGenerator.Preferences(
                11L,
                Set.of(),
                Map.of(),
                PreferenceSignals.from(answers),
                RecipientPriority.from(answers)),
            NOW);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().products()).extracting(p -> p.id()).containsExactly("4", "5");
  }

  @Test
  void understandsParentLabelsAndRejectsAccessories() {
    for (String label : List.of("부모님", "엄마", "아버지", " Parents ")) {
      var policy = RecipientPriority.from(Map.of("recipient", label));
      assertThat(policy.pairTier("꽃다발", "케익")).isGreaterThan(policy.pairTier("노트", "펜"));
      assertThat(policy.pairTier("꽃다발 스티커", "케이크 토퍼")).isZero();
    }
    assertThat(RecipientPriority.from(Map.of()).specified()).isFalse();
    assertThat(RecipientPriority.from(Map.of("recipient", "친구")).pairTier("꽃다발", "케이크")).isZero();
  }
}
