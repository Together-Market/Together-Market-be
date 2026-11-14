package com.togethermarket.recommendation.service.candidate;

import static com.togethermarket.recommendation.service.candidate.CandidateFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.togethermarket.product.dto.request.ProductWriteRequest;
import com.togethermarket.recommendation.service.*;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ZeroPriceRecommendationTest {
  @Test
  void zeroPriceAcceptedByRegistrationSurvivesGenerationRankingAndSavedRead() {
    var body = new HashMap<String, Object>();
    body.put("name", "무료 상품");
    body.put("description", "설명");
    body.put("image_url", null);
    body.put("price_amount", 0);
    body.put("preparation_minutes", 0);
    body.put("sale_status", "ON_SALE");
    body.put("purpose_category_ids", List.of("11"));
    var registered = ProductWriteRequest.parse(body, true);
    var data = new CandidateFixtures();
    data.products.clear();
    data.products.put(1L, product(1, 1, registered.priceAmount().longValueExact()));
    data.products.put(2L, product(2, 2, 10000));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(200, 12, 8, 1500));
    var candidates =
        generator.generate(
            data.purchase,
            data.products.keySet(),
            new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
            NOW);
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().totalAmount()).isEqualTo(10000);
    var ranker =
        new HybridRecommendationService(
            (context, input) ->
                input.stream()
                    .map(
                        c ->
                            new RecommendationInput.Evaluation(
                                c.candidate_id(),
                                5,
                                5,
                                5,
                                5,
                                "ALLOWED",
                                c.products().stream().map(RecommendationInput.Product::id).toList(),
                                "무료 상품과 함께 추천"))
                    .toList(),
            RankingModel.baseline());
    var ranked =
        ranker.recommend(
            new RecommendationInput.Context("생일", 10000, PICKUP.toString(), Map.of()), candidates);
    assertThat(ranked).hasSize(1);
    var saved =
        new SavedCandidate(
            1L,
            data.purchase.id(),
            1,
            ranked.getFirst().reason(),
            List.of(new SavedItem(1L, 1L, 1, 0), new SavedItem(2L, 2L, 1, 10000)));
    var checked =
        new CandidateRevalidator(data, (id, pickup, now) -> ProductEligibility.Result.available())
            .check(data.purchase, List.of(saved), NOW)
            .getFirst();
    assertThat(checked.available()).isTrue();
    assertThat(checked.totalAmount()).isEqualTo(10000);
    assertThat(checked.priceChanged()).isFalse();
  }

  @Test
  void twoFreeProductsRemainAValidPair() {
    var data = new CandidateFixtures();
    data.products.clear();
    data.products.put(1L, product(1, 1, 0));
    data.products.put(2L, product(2, 2, 0));
    var generator =
        new CandidateGenerator(
            data,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(200, 12, 8, 1500));
    assertThat(
            generator.generate(
                data.purchase,
                data.products.keySet(),
                new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
                NOW))
        .singleElement()
        .satisfies(c -> assertThat(c.totalAmount()).isZero());
  }

  @Test
  void negativePricesAreStillRejectedAcrossSnapshots() {
    assertThatThrownBy(() -> product(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SavedItem(1L, 1L, 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecommendationInput.Product("1", "2", "상품", "설명", "업종", -1, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
