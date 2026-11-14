package com.togethermarket.recommendation.service.customer;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.product.entity.*;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.store.entity.*;
import com.togethermarket.user.entity.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Dedicated test MySQL only; each fixture is rolled back after the read checks. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate",
      "recommendation.customer-read.enabled=true",
      "recommendation.customer-read.visible-sale-statuses=ON_SALE,SOLD_OUT,PAUSED",
      "recommendation.customer-read.coordinate-policy=DETAIL_NULL_MAP_ERROR"
    })
@Transactional
class CustomerReadDatabaseIntegrationTest {
  @Autowired EntityManager em;
  @Autowired CustomerReadService service;
  @Autowired JdbcTemplate jdbc;
  long customer, productA, productB, storeA, storeB, candidate;

  <T> T save(T entity) {
    em.persist(entity);
    em.flush();
    return entity;
  }

  @BeforeEach
  void seed() {
    customer =
        save(new User(UUID.randomUUID() + "@test.invalid", "test", "고객", UserRole.CUSTOMER))
            .getId();
    long owner =
        save(new User(UUID.randomUUID() + "@test.invalid", "test", "점주", UserRole.OWNER)).getId();
    storeA =
        save(new Store(
                owner, "꽃집", "flower", "용인시", BigDecimal.ZERO, BigDecimal.ZERO, "031-000-0001"))
            .getId();
    storeB =
        save(new Store(
                owner, "빵집", "bakery", "용인시", BigDecimal.ZERO, BigDecimal.ONE, "031-000-0002"))
            .getId();
    productA =
        save(new Product(storeA, "꽃", "설명", null, BigDecimal.ZERO, 0, SaleStatus.SOLD_OUT)).getId();
    productB =
        save(new Product(storeB, "빵", "설명", null, new BigDecimal("4000"), 30, SaleStatus.ON_SALE))
            .getId();
    save(new ProductPurposeTag(productA, 11L));
    save(
        new StoreHour(
            storeA,
            (short) 1,
            null,
            HourType.PICKUP,
            false,
            LocalTime.of(22, 0),
            LocalTime.of(1, 0),
            (short) 1));
    save(
        new StoreHour(
            storeA,
            null,
            LocalDate.of(2026, 11, 14),
            HourType.BUSINESS,
            true,
            null,
            null,
            (short) 0));
    long request =
        save(new PurchaseRequest(
                customer,
                11L,
                new BigDecimal("10000"),
                LocalDateTime.of(2026, 11, 14, 15, 0),
                RecommendationStatus.READY,
                null,
                LocalDateTime.of(2026, 11, 14, 10, 0),
                "[]"))
            .getId();
    candidate = save(new RecommendationCandidate(request, 1, "추천 이유")).getId();
    // Deliberately opposite to product/store ID ordering.
    save(new RecommendationItem(candidate, productB, 1, new BigDecimal("3500")));
    save(new RecommendationItem(candidate, productA, 1, new BigDecimal("1000")));
    em.clear();
  }

  @Test
  void actualRepositoryQueriesReturnCurrentValuesSchedulesAndOrderedMap() {
    var product = service.product(Long.toString(productA), customer);
    assertThat(product.priceAmount()).isZero();
    assertThat(product.saleStatus()).isEqualTo(SaleStatus.SOLD_OUT);
    assertThat(product.purposeCategoryIds()).containsExactly("11");
    var hours = service.store(Long.toString(storeA), customer).hours();
    assertThat(hours).hasSize(2);
    assertThat(hours.getFirst().endDayOffset()).isEqualTo((short) 1);
    assertThat(hours.get(1).closed()).isTrue();
    var locations = service.locations(Long.toString(candidate), customer);
    assertThat(locations.stores())
        .extracting(s -> s.id())
        .containsExactly(Long.toString(storeB), Long.toString(storeA));
    assertThat(locations.distanceMeters()).isEqualTo(111195);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommendation_items WHERE recommendation_candidate_id=?",
                Long.class,
                candidate))
        .isEqualTo(2);
  }

  @Test
  void changedPriceAndStatusAreReadWithoutAlteringSavedRecommendation() {
    jdbc.update("UPDATE products SET price_amount=4500,sale_status='PAUSED' WHERE id=?", productB);
    var detail = service.product(Long.toString(productB), customer);
    assertThat(detail.priceAmount()).isEqualTo(4500);
    assertThat(detail.saleStatus()).isEqualTo(SaleStatus.PAUSED);
    assertThat(service.locations(Long.toString(candidate), customer).stores()).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT unit_price_snapshot FROM recommendation_items WHERE recommendation_candidate_id=? AND product_id=?",
                BigDecimal.class,
                candidate,
                productB))
        .isEqualByComparingTo("3500");
  }

  @Test
  void foreignCustomerIsRejectedAndMissingCoordinatesReturnUnavailable() {
    assertThatThrownBy(() -> service.locations(Long.toString(candidate), customer + 10000))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    jdbc.update("UPDATE stores SET latitude=NULL,longitude=NULL WHERE id=?", storeA);
    em.clear();
    assertThat(service.store(Long.toString(storeA), customer).latitude()).isNull();
    var locations = service.locations(Long.toString(candidate), customer);
    assertThat(locations.distanceMeters()).isNull();
    assertThat(locations.locationStatus()).isEqualTo("UNAVAILABLE");
  }
}
