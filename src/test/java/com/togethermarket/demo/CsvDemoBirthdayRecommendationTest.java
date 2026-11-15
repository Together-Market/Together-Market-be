package com.togethermarket.demo;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.*;
import com.togethermarket.recommendation.service.candidate.*;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exercises the shipped catalog through retrieval and ranking; AI scores are deterministic. */
class CsvDemoBirthdayRecommendationTest {
  @Test
  void grandmotherBirthdayUsesWholeCakeAndFlowersAtSixtyNinetyAndOneTwentyThousand()
      throws Exception {
    verifyCatalog(Map.of("recipient", "할머니", "category_preference", "꽃"), false);
  }

  @Test
  void knittingGrandmotherGetsMaterialsAndWholeCakeInsteadOfCoffeeStationeryOrFlowers()
      throws Exception {
    verifyCatalog(
        Map.of("recipient", "뜨개질을 좋아하시는 할머니", "category_preference", "뜨개질 재료와 홀케이크"), true);
  }

  private void verifyCatalog(Map<String, String> answers, boolean knitting) throws Exception {
    var bundle = CsvDemoBundle.load();
    var products = new HashMap<Long, Product>();
    var onSale = new HashSet<Long>();
    long storeId = 0;
    long productId = 0;
    for (var store : bundle.catalog().stores()) {
      storeId++;
      String identity = StoreIdentity.key(storeId, store.name(), store.address());
      for (var p : store.products()) {
        productId++;
        products.put(
            productId,
            new Product(
                productId,
                storeId,
                p.name(),
                p.description(),
                store.businessType(),
                p.priceAmount(),
                Set.copyOf(p.purposeCategoryIds()),
                Double.valueOf(store.latitude()),
                Double.valueOf(store.longitude()),
                identity));
        if (p.saleStatus().equals("ON_SALE")) onSale.add(productId);
      }
    }
    var generator =
        new CandidateGenerator(
            ids -> {
              var batch = new HashMap<Long, Product>();
              ids.forEach(id -> batch.put(id, products.get(id)));
              return batch;
            },
            (id, pickup, now) ->
                onSale.contains(id)
                    ? ProductEligibility.Result.available()
                    : ProductEligibility.Result.unavailable("NOT_ON_SALE"),
            new CandidateGenerator.Limits(100, 12, 8, 1500));
    var ranker =
        new HybridRecommendationService(
            (context, rows) ->
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
                                "홀케이크를 포함한 생신 선물 구성입니다."))
                    .toList(),
            RankingModel.baseline());
    var preferences =
        new CandidateGenerator.Preferences(
            11L,
            Set.of(),
            Map.of(),
            PreferenceSignals.from(answers),
            RecipientPriority.from(answers),
            GiftComposition.from("생일"));
    var pickup = OffsetDateTime.parse("2026-11-16T18:00:00+09:00");
    long previous = 0;
    for (long budget : List.of(60000L, 90000L, 120000L)) {
      var candidates =
          generator.generate(
              new Purchase(1L, 7L, budget, pickup, "RECOMMENDING"),
              products.keySet(),
              preferences,
              Instant.parse("2026-11-15T00:00:00Z"));
      var results =
          ranker.recommend(
              new RecommendationInput.Context("생일", budget, pickup.toString(), answers),
              candidates);
      assertThat(results).isNotEmpty();
      assertThat(results)
          .allSatisfy(
              r -> {
                assertThat(r.candidate().totalAmount()).isLessThanOrEqualTo(budget);
                if (knitting)
                  assertThat(r.candidate().products())
                      .anyMatch(p -> RecipientPriority.knittingMaterial(p.name()));
                assertThat(
                        r.candidate().products().stream()
                            .filter(p -> GiftComposition.wholeCake(p.name(), p.description()))
                            .count())
                    .isEqualTo(1);
              });
      var top = results.getFirst().candidate();
      assertThat(top.products())
          .anyMatch(p -> preferences.recipient().productKind(p.name()) == (knitting ? 4 : 1));
      assertThat(top.totalAmount()).isGreaterThan(previous);
      if (budget == 120000) assertThat(top.totalAmount()).isEqualTo(120000);
      previous = top.totalAmount();
      System.out.println(
          (knitting ? "KNITTING_CATALOG budget=" : "BIRTHDAY_CATALOG budget=")
              + budget
              + " total="
              + top.totalAmount()
              + " products="
              + top.products().stream().map(p -> p.name() + " " + p.price_amount()).toList());
    }
  }
}
