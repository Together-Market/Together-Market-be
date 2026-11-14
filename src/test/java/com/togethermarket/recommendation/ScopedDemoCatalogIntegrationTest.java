package com.togethermarket.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.StoreDistanceCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verify actual persisted availability, including the previous five-store sample. */
@EnabledIfEnvironmentVariable(named = "PUBLIC_SCOPED_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${PUBLIC_SCOPED_DB_URL}",
      "spring.datasource.username=${PUBLIC_DEMO_DB_USERNAME}",
      "spring.datasource.password=${PUBLIC_DEMO_DB_PASSWORD}",
      "recommendation.persistence.enabled=true",
      "reservation.approval-window=PT30M",
      "reservation.expiry-scheduler.enabled=false"
    })
class ScopedDemoCatalogIntegrationTest {
  @Test
  void actualCatalogCoversBothPreferredFamiliesAndDoesNotFabricateAnAffordablePair() {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM products WHERE sale_status='ON_SALE' ORDER BY id", Long.class);
    var pickup =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            .plusDays(2)
            .atTime(15, 0)
            .atOffset(java.time.ZoneOffset.ofHours(9));
    var generator =
        new com.togethermarket.recommendation.service.candidate.CandidateGenerator(
            catalog,
            eligibility,
            new com.togethermarket.recommendation.service.candidate.CandidateGenerator.Limits(
                500, 12, 8, 1500));
    for (var scenario :
        java.util.List.of(
            new Object[] {60000L, "꽃과 디저트"},
            new Object[] {20000L, "음료와 디저트"},
            new Object[] {30000L, "음료와 디저트"})) {
      long budget = (Long) scenario[0];
      var signals =
          com.togethermarket.recommendation.service.candidate.PreferenceSignals.from(
              java.util.Map.of("category_preference", (String) scenario[1]));
      var result =
          generator.generate(
              new com.togethermarket.recommendation.service.candidate.CandidateData.Purchase(
                  1L, 1L, budget, pickup, "RECOMMENDING"),
              ids,
              new com.togethermarket.recommendation.service.candidate.CandidateGenerator
                  .Preferences(11L, java.util.Set.of(), java.util.Map.of(), signals),
              java.time.Instant.now());
      if (budget == 20000) {
        assertThat(result).isEmpty();
        continue;
      }
      assertThat(result).isNotEmpty();
      var namePairs = new java.util.HashMap<java.util.List<String>, Integer>();
      for (var pair : result) {
        assertThat(pair.products().getFirst().store_identity())
            .isNotEqualTo(pair.products().getLast().store_identity());
        assertThat(
                signals.accepts(
                    signals.matches(pair.products().getFirst().name()),
                    signals.matches(pair.products().getLast().name())))
            .isTrue();
        namePairs.merge(
            com.togethermarket.recommendation.service.candidate.CombinationDiversity.names(
                pair.products().stream().map(p -> p.name()).toList()),
            1,
            Integer::sum);
      }
      assertThat(namePairs.values()).allMatch(n -> n <= 2);
      assertThat(namePairs.size()).isGreaterThanOrEqualTo(3);
    }
    var duplicateIds =
        jdbc.queryForList("SELECT id FROM products WHERE store_id IN (17588,18297)", Long.class);
    var duplicateProducts = catalog.findProducts(java.util.Set.copyOf(duplicateIds));
    assertThat(duplicateProducts.values().stream().map(p -> p.storeIdentity()).distinct())
        .hasSize(1);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired com.togethermarket.product.repository.ProductRepository products;
  @Autowired com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;
  @Autowired com.togethermarket.recommendation.service.persistence.JpaCatalogSource catalog;

  @Autowired
  com.togethermarket.recommendation.service.persistence.CommonProductEligibility eligibility;

  @Test
  void fullCatalogBeyondTwoHundredGeneratesOnlySameAreaPairsWithinWorkerBudget() {
    var requests =
        org.mockito.Mockito.mock(
            com.togethermarket.recommendation.repository.PurchaseRequestRepository.class);
    var pickup =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(2).atTime(15, 0);
    var request =
        new com.togethermarket.recommendation.entity.PurchaseRequest(
            1L,
            14L,
            java.math.BigDecimal.valueOf(60000),
            pickup,
            com.togethermarket.recommendation.entity.RecommendationStatus.RECOMMENDING,
            null,
            java.time.LocalDateTime.now(),
            "[]");
    org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 1L);
    org.mockito.Mockito.when(requests.findById(1L)).thenReturn(java.util.Optional.of(request));
    var source =
        new com.togethermarket.recommendation.service.persistence.JpaRecommendationInputSource(
            requests,
            categories,
            products,
            new com.togethermarket.recommendation.service.persistence.PurchaseAnswerCodec(),
            500);
    var start = System.nanoTime();
    var input = source.load(1L);
    assertThat(input.productIds())
        .containsExactlyElementsOf(
            jdbc.queryForList(
                "SELECT id FROM products WHERE sale_status='ON_SALE' AND price_amount<=60000 ORDER BY id",
                Long.class));
    assertThat(input.productIds()).hasSize(4956);
    var generator =
        new com.togethermarket.recommendation.service.candidate.CandidateGenerator(
            catalog,
            eligibility,
            new com.togethermarket.recommendation.service.candidate.CandidateGenerator.Limits(
                500, 12, 8, 1500));
    var result =
        generator.generate(
            input.purchase(), input.productIds(), input.preferences(), java.time.Instant.now());
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    assertThat(result).hasSize(12);
    var ids =
        result.stream()
            .flatMap(c -> c.products().stream())
            .map(p -> Long.valueOf(p.id()))
            .collect(java.util.stream.Collectors.toSet());
    var snapshots = catalog.findProducts(ids);
    var selectedAreas =
        new java.util.HashSet<
            com.togethermarket.recommendation.service.candidate.RecommendationArea>();
    for (var candidate : result) {
      var first =
          com.togethermarket.recommendation.service.candidate.RecommendationArea.of(
              snapshots.get(Long.valueOf(candidate.products().getFirst().id())));
      var second =
          com.togethermarket.recommendation.service.candidate.RecommendationArea.of(
              snapshots.get(Long.valueOf(candidate.products().getLast().id())));
      assertThat(first).isNotNull().isEqualTo(second);
      assertThat(candidate.totalAmount()).isLessThanOrEqualTo(60000);
      selectedAreas.add(first);
    }
    assertThat(selectedAreas)
        .containsExactlyInAnyOrder(
            com.togethermarket.recommendation.service.candidate.RecommendationArea.BOJEONG,
            com.togethermarket.recommendation.service.candidate.RecommendationArea.DONGBAEK);
    assertThat(ids).anyMatch(id -> id > input.productIds().get(199));
    System.out.printf(
        "Full catalog: %d products, %d pairs, %dms, areas=%s%n",
        input.productIds().size(), result.size(), elapsed, selectedAreas);
    assertThat(elapsed)
        .as("Local preprocessing leaves time for AI within the 40-second worker lease")
        .isLessThan(20000);
  }

  @Test
  void everyOnSaleDemoProductIsInsideOneOfTheApprovedAreas() {
    var rows =
        jdbc.queryForList(
            "SELECT p.id,s.latitude,s.longitude FROM products p JOIN stores s ON s.id=p.store_id JOIN users u ON u.id=s.owner_id WHERE p.sale_status='ON_SALE' AND (u.email LIKE 'yongin-%@demo.together.invalid' OR u.email LIKE 'owner-%@demo.together.invalid')");
    assertThat(rows).hasSize(4956);
    double[][] anchors = {
      {37.3211736204431, 127.110115610261},
      {37.2758, 127.1497},
      {37.2779978497857, 127.15160126343},
      {37.2745843001736, 127.150512004189}
    };
    for (var row : rows) {
      assertThat(row.get("latitude")).isNotNull();
      assertThat(row.get("longitude")).isNotNull();
      double lat = ((Number) row.get("latitude")).doubleValue();
      double lon = ((Number) row.get("longitude")).doubleValue();
      boolean inside = false;
      for (var anchor : anchors) {
        var distance = StoreDistanceCalculator.straightLineMeters(lat, lon, anchor[0], anchor[1]);
        if (distance.isPresent() && distance.getAsLong() <= 500) inside = true;
      }
      assertThat(inside).as("Product %s is inside approved 500m coverage", row.get("id")).isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM products p JOIN stores s ON s.id=p.store_id JOIN users u ON u.id=s.owner_id WHERE u.email LIKE 'owner-%@demo.together.invalid' AND p.sale_status<>'PAUSED'",
                Long.class))
        .isZero();
  }
}
