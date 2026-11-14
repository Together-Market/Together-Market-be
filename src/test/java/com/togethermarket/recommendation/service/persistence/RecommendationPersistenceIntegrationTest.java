package com.togethermarket.recommendation.service.persistence;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.RecommendationInput;
import com.togethermarket.recommendation.service.job.*;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requires a dedicated disposable MySQL database; never set MYSQL_TEST_URL to a service database.
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@Import(RecommendationPersistenceIntegrationTest.Configuration.class)
class RecommendationPersistenceIntegrationTest {
  static final Instant START = Instant.parse("2026-11-13T09:00:00Z");

  static class DispatchProbe {
    final List<Long> requests = new ArrayList<>();
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> value = new AtomicReference<>(START);

    @Override
    public Instant instant() {
      return value.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Configuration {
    @Bean
    DispatchProbe dispatchProbe() {
      return new DispatchProbe();
    }

    @Bean
    JpaPurchaseCreationService creationService(
        PurchaseRequestRepository purchases,
        UserRepository users,
        PurposeCategoryRepository categories,
        TestClock clock,
        JpaRecommendationJobStore store,
        DispatchProbe probe) {
      var validator =
          new PurchaseConditionValidator(
              new PurposeQuestionService(
                  new PurposeCategoryService(new JpaPurposeCategorySource(categories)),
                  Map.of(11L, "선호 상품 종류")),
              clock);
      var hook =
          new RecommendationAfterCommit(
              id -> {
                probe.requests.add(id);
                var lease =
                    store
                        .claim(id, UUID.randomUUID(), clock.instant(), Duration.ofSeconds(40))
                        .orElseThrow();
                store.complete(lease, List.of(), clock.instant());
              });
      return new JpaPurchaseCreationService(
          purchases, users, validator, new PurchaseAnswerCodec(), hook);
    }

    @Bean
    TestClock clock() {
      return new TestClock();
    }

    @Bean
    JpaRecommendationJobStore store(
        PurchaseRequestRepository p,
        RecommendationCandidateRepository c,
        RecommendationItemRepository i,
        com.togethermarket.product.repository.ProductRepository products,
        com.togethermarket.product.service.ProductAvailabilityService availability,
        TestClock clock) {
      return new JpaRecommendationJobStore(p, c, i, clock, products, availability);
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PurchaseRequestRepository purchases;
  @Autowired RecommendationCandidateRepository candidates;
  @Autowired RecommendationItemRepository items;
  @Autowired JpaRecommendationJobStore store;
  @Autowired TestClock clock;
  @Autowired JpaPurchaseCreationService creationService;
  @Autowired DispatchProbe dispatchProbe;
  @Autowired PlatformTransactionManager transactions;
  long customer, shopA, shopB, productA, productB, request;

  @BeforeEach
  void seed() {
    clock.value.set(START);
    dispatchProbe.requests.clear();
    customer =
        insert(
            "INSERT INTO users(email,password_hash,name,role) VALUES (?, 'test', 'test', 'CUSTOMER')",
            UUID.randomUUID() + "@test.invalid");
    shopA =
        insert(
            "INSERT INTO stores(owner_id,name,business_type,address,phone) VALUES (?, 'a', 'flower', 'address', '010')",
            customer);
    shopB =
        insert(
            "INSERT INTO stores(owner_id,name,business_type,address,phone) VALUES (?, 'b', 'bakery', 'address', '010')",
            customer);
    productA =
        insert(
            "INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES (?, 'a', 'gift', 0, 0, 'ON_SALE')",
            shopA);
    productB =
        insert(
            "INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES (?, 'b', 'gift', 4000, 0, 'ON_SALE')",
            shopB);
    for (long shop : List.of(shopA, shopB)) {
      for (String type : List.of("BUSINESS", "PICKUP")) {
        jdbc.update(
            "INSERT INTO store_hours(store_id,target_date,hour_type,is_closed,opens_at,closes_at,end_day_offset) VALUES (?, '2026-11-14', ?, false, '00:00:00', '23:59:59', 0)",
            shop,
            type);
      }
    }
    request =
        purchases
            .saveAndFlush(
                new PurchaseRequest(
                    customer,
                    11L,
                    BigDecimal.valueOf(10000),
                    LocalDateTime.of(2026, 11, 14, 15, 0),
                    RecommendationStatus.RECOMMENDING,
                    null,
                    KoreanDateTime.toDatabase(START),
                    "[]"))
            .getId();
  }

  long insert(String sql, Object... values) {
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  @AfterEach
  void cleanup() {
    if (request != 0) {
      jdbc.update(
          "DELETE i FROM recommendation_items i JOIN recommendation_candidates c ON c.id=i.recommendation_candidate_id WHERE c.purchase_request_id=?",
          request);
      jdbc.update("DELETE FROM recommendation_candidates WHERE purchase_request_id=?", request);
      jdbc.update("DELETE FROM purchase_requests WHERE id=?", request);
    }
    jdbc.update("DELETE FROM products WHERE id IN (?,?)", productA, productB);
    jdbc.update("DELETE FROM store_hours WHERE store_id IN (?,?)", shopA, shopB);
    jdbc.update("DELETE FROM stores WHERE id IN (?,?)", shopA, shopB);
    jdbc.update("DELETE FROM users WHERE id=?", customer);
  }

  RecommendationJobStore.Lease claim() {
    return store.claim(request, UUID.randomUUID(), START, Duration.ofSeconds(40)).orElseThrow();
  }

  RankedCandidate result(long secondProduct) {
    return new RankedCandidate(
        new RecommendationInput.Candidate(
            "pair",
            List.of(
                new RecommendationInput.Product(
                    Long.toString(productA), Long.toString(shopA), "a", "", "flower", 0, true),
                new RecommendationInput.Product(
                    Long.toString(secondProduct),
                    Long.toString(shopB),
                    "b",
                    "",
                    "bakery",
                    4000,
                    true)),
            null,
            true),
        "선물 조합",
        80,
        "baseline",
        "manual-v1");
  }

  @Test
  void savesItemsAndReadyTogetherAndCanReadThemBack() {
    var lease = claim();
    assertThat(store.complete(lease, List.of(result(productB)), START)).isTrue();
    assertThat(store.complete(lease, List.of(result(productB)), START)).isFalse();
    assertThat(purchases.findById(request).orElseThrow().getStatus())
        .isEqualTo(RecommendationStatus.READY);
    var saved = candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request);
    assertThat(saved).hasSize(1);
    var savedItems =
        items.findByRecommendationCandidateIdInOrderByIdAsc(List.of(saved.getFirst().getId()));
    assertThat(savedItems).hasSize(2);
    assertThat(savedItems.getFirst().getUnitPriceSnapshot()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void twoTransactionsCannotClaimTheSameRequest() throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return store.claim(request, UUID.randomUUID(), START, Duration.ofSeconds(40));
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return store.claim(request, UUID.randomUUID(), START, Duration.ofSeconds(40));
              });
      gate.countDown();
      assertThat(
              List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)).stream()
                  .filter(java.util.Optional::isPresent)
                  .count())
          .isEqualTo(1);
    }
  }

  @Test
  void failedItemInsertRollsBackCandidateAndStateBeforeSeparateFailureTransition() {
    var lease = claim();
    jdbc.execute(
        "CREATE TRIGGER fail_test_recommendation_item BEFORE INSERT ON recommendation_items FOR EACH ROW BEGIN IF NEW.product_id = "
            + productB
            + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected test insert failure'; END IF; END");
    try {
      assertThatThrownBy(() -> store.complete(lease, List.of(result(productB)), START))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("DROP TRIGGER fail_test_recommendation_item");
    }
    assertThat(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request)).isEmpty();
    assertThat(purchases.findById(request).orElseThrow().getStatus())
        .isEqualTo(RecommendationStatus.RECOMMENDING);
    assertThat(store.fail(lease, RecommendationFailureCode.RESULT_PERSISTENCE_FAILED, START))
        .isTrue();
    assertThat(purchases.findById(request).orElseThrow().getFailureCode())
        .isEqualTo("RESULT_PERSISTENCE_FAILED");
  }

  @Test
  void expirationOfLostDispatchPersistsWithoutCreatingCandidates() {
    clock.value.set(START.plusSeconds(40));
    assertThat(store.expire(START, Duration.ofSeconds(40), 100)).isGreaterThanOrEqualTo(1);
    assertThat(purchases.findById(request).orElseThrow().getFailureCode())
        .isEqualTo("RECOMMENDATION_TIMEOUT");
    assertThat(store.claim(request, UUID.randomUUID(), START, Duration.ofSeconds(40))).isEmpty();
  }

  @Test
  void emptyRecommendationIsReadyInDatabase() {
    assertThat(store.complete(claim(), List.of(), START)).isTrue();
    assertThat(purchases.findById(request).orElseThrow().getStatus())
        .isEqualTo(RecommendationStatus.READY);
    assertThat(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request)).isEmpty();
  }

  @Test
  void priceChangeWithinBudgetUsesCurrentPriceSnapshot() {
    var lease = claim();
    jdbc.update("UPDATE products SET price_amount=6000 WHERE id=?", productB);
    assertThat(store.complete(lease, List.of(result(productB)), START)).isTrue();
    var saved = candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request);
    assertThat(saved).hasSize(1);
    assertThat(
            items
                .findByRecommendationCandidateIdInOrderByIdAsc(List.of(saved.getFirst().getId()))
                .stream()
                .filter(i -> i.getProductId().equals(productB))
                .findFirst()
                .orElseThrow()
                .getUnitPriceSnapshot())
        .isEqualByComparingTo("6000");
  }

  @Test
  void newlyOverBudgetCandidateIsRemovedBeforeSaving() {
    var lease = claim();
    jdbc.update("UPDATE products SET price_amount=10001 WHERE id=?", productB);
    assertThat(store.complete(lease, List.of(result(productB)), START)).isTrue();
    assertThat(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request)).isEmpty();
    assertThat(purchases.findById(request).orElseThrow().getStatus())
        .isEqualTo(RecommendationStatus.READY);
  }

  @Test
  void soldOutDuringAiEvaluationIsRemovedBeforeSaving() {
    var lease = claim();
    jdbc.update("UPDATE products SET sale_status='SOLD_OUT' WHERE id=?", productB);
    assertThat(store.complete(lease, List.of(result(productB)), START)).isTrue();
    assertThat(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request)).isEmpty();
  }

  @Test
  void pickupScheduleChangeDuringEvaluationIsRechecked() {
    var lease = claim();
    jdbc.update(
        "UPDATE store_hours SET closes_at='14:00:00' WHERE store_id=? AND hour_type='PICKUP'",
        shopB);
    assertThat(store.complete(lease, List.of(result(productB)), START)).isTrue();
    assertThat(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(request)).isEmpty();
  }

  CreatePurchaseRequest creationInput() {
    return new CreatePurchaseRequest(
        "11",
        10000L,
        "2026-11-14T06:00:00Z",
        List.of(
            new CreatePurchaseRequest.Answer("style", "따뜻함"),
            new CreatePurchaseRequest.Answer("recipient", "친구"),
            new CreatePurchaseRequest.Answer("category_preference", "꽃")));
  }

  @Test
  void creationCommitsKstJsonThenDispatchesIntoIndependentTransaction() {
    var response = creationService.create(creationInput(), customer);
    try {
      assertThat(response.status()).isEqualTo("RECOMMENDING");
      assertThat(dispatchProbe.requests).containsExactly(response.id());
      var saved = purchases.findById(response.id()).orElseThrow();
      assertThat(saved.getStatus()).isEqualTo(RecommendationStatus.READY);
      assertThat(saved.getPreferredPickupAt()).isEqualTo(LocalDateTime.of(2026, 11, 14, 15, 0));
      assertThat(new PurchaseAnswerCodec().decode(saved.getAnswers()).getLast().label())
          .isEqualTo("선호 상품 종류");
    } finally {
      jdbc.update("DELETE FROM purchase_requests WHERE id=?", response.id());
    }
  }

  @Test
  void purchaseRollbackDoesNotDispatchOrLeaveRow() {
    var id =
        new TransactionTemplate(transactions)
            .execute(
                tx -> {
                  var response = creationService.create(creationInput(), customer);
                  tx.setRollbackOnly();
                  return response.id();
                });
    assertThat(purchases.findById(id)).isEmpty();
    assertThat(dispatchProbe.requests).isEmpty();
  }
}
