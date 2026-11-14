package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.RecommendationJson;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationQualityTest {
  Product productAt(long id, long store, String name, long price, String identity) {
    return new Product(
        id, store, name, null, "업종", price, Set.of(11L), 37.32117, 127.11011, identity);
  }

  @Test
  void explicitTwoFamiliesMustBothBePresentButAlternativesNeedNot() {
    var signals = PreferenceSignals.from(Map.of("category_preference", "꽃과 디저트가 어울리는 선물"));
    assertThat(signals.requireAll()).isTrue();
    assertThat(signals.accepts(signals.matches("파스텔 꽃다발"), signals.matches("쿠키 선물 상자"))).isTrue();
    assertThat(signals.accepts(signals.matches("미니 케이크"), signals.matches("양말 선물 세트"))).isFalse();
    var either = PreferenceSignals.from(Map.of("category_preference", "꽃 또는 디저트"));
    assertThat(either.requireAll()).isFalse();
    var negated = PreferenceSignals.from(Map.of("category_preference", "꽃 말고 커피"));
    assertThat(negated.excludes("파스텔 꽃다발")).isTrue();
    assertThat(negated.matches("커피 두 잔 세트")).isNotZero();
    assertThat(negated.matches("파스텔 꽃다발")).isZero();
    var noCoffee =
        PreferenceSignals.from(Map.of("category_preference", "차", "excluded_products", "커피"));
    assertThat(noCoffee.excludes("커피 두 잔 세트")).isTrue();
    assertThat(noCoffee.excludes("차 두 잔 세트")).isFalse();
    assertThat(
            PreferenceSignals.from(Map.of("category_preference", "차분한 친구의 산책 선물"))
                .matches("작은 책 선물"))
        .isZero();
  }

  @Test
  void requestedPairIsFoundPastIrrelevantProductsAndUnaffordablePairIsEmpty() {
    var data = new CandidateFixtures();
    data.products.clear();
    for (long i = 1; i <= 250; i++)
      data.products.put(i, productAt(i, i, "양말 " + i, 1000, "id:" + i));
    data.products.put(251L, productAt(251, 251, "커피 두 잔 세트", 9000, "id:251"));
    data.products.put(252L, productAt(252, 252, "쿠키 선물 상자", 16000, "id:252"));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(50, 12, 8, 1500));
    var prefs =
        new CandidateGenerator.Preferences(
            11L,
            Set.of(),
            Map.of(),
            PreferenceSignals.from(Map.of("category_preference", "음료와 디저트")));
    assertThat(
            generator.generate(
                new Purchase(1L, 7L, 20000, PICKUP, "RECOMMENDING"),
                data.products.keySet(),
                prefs,
                NOW))
        .isEmpty();
    var result =
        generator.generate(
            new Purchase(1L, 7L, 30000, PICKUP, "RECOMMENDING"),
            data.products.keySet(),
            prefs,
            NOW);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().products()).extracting(p -> p.id()).containsExactly("251", "252");
  }

  @Test
  void sameMerchantAcrossSourcesIsBlockedAtGenerationAndRevalidation() {
    String a = StoreIdentity.key(1L, "[시연] 동백문고", "경기도 용인시 기흥구 동백중앙로 213");
    String b = StoreIdentity.key(2L, "동백 문고", "경기 용인시 기흥구 동백중앙로 213, 2층");
    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(StoreIdentity.key(3L, "다른서점", "경기도 용인시 기흥구 동백중앙로 213"));
    assertThat(a).isNotEqualTo(StoreIdentity.key(4L, "동백문고", "경기도 용인시 기흥구 동백중앙로 215"));
    var data = new CandidateFixtures();
    data.products.clear();
    data.products.put(1L, productAt(1, 1, "노트", 3000, a));
    data.products.put(2L, productAt(2, 2, "책", 4000, b));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(500, 12, 8, 1500));
    assertThat(
            generator.generate(
                data.purchase,
                data.products.keySet(),
                new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
                NOW))
        .isEmpty();
    var checked =
        new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available())
            .check(data.purchase, List.of(saved(1, 1, 1, 2)), NOW);
    assertThat(checked.getFirst().available()).isFalse();
    assertThat(checked.getFirst().reasons())
        .extracting(UnavailableReason::code)
        .contains("DUPLICATE_STORE_IDENTITY");
  }

  @Test
  void internalMerchantIdentityDoesNotChangeGeminiWireContract() {
    var product =
        new com.togethermarket.recommendation.service.RecommendationInput.Product(
            "1", "2", "상품", "설명", "업종", 1000, true, "private-store-identity");
    assertThat(RecommendationJson.parse(RecommendationJson.stringify(product)))
        .hasSize(7)
        .doesNotContainKey("store_identity");
    assertThat(RecommendationJson.stringify(product)).doesNotContain("private-store-identity");
  }

  @Test
  void preselectionCapsRepeatedMerchantsAndIdenticalProductNames() {
    var data = new CandidateFixtures();
    data.products.clear();
    String[] names = {"꽃다발", "미니 화분", "꽃바구니", "쿠키", "케이크", "빵"};
    for (long i = 1; i <= 60; i++)
      data.products.put(i, productAt(i, i, names[(int) (i % 6)], 1000, "id:" + i));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(500, 12, 8, 1500));
    var result =
        generator.generate(
            data.purchase,
            data.products.keySet(),
            new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
            NOW);
    assertThat(result).hasSize(12);
    var merchants = new HashMap<String, Integer>();
    var combinations = new HashMap<List<String>, Integer>();
    result.forEach(
        c -> {
          c.products().forEach(p -> merchants.merge(p.store_identity(), 1, Integer::sum));
          combinations.merge(
              CombinationDiversity.names(c.products().stream().map(p -> p.name()).toList()),
              1,
              Integer::sum);
        });
    assertThat(merchants.values()).allMatch(n -> n <= 3);
    assertThat(combinations.values()).allMatch(n -> n <= 2);
    assertThat(combinations).hasSizeGreaterThanOrEqualTo(6);
  }
}
