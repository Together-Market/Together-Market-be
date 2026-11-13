package com.togethermarket.reservation;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.reservation.dto.request.CreateReservationRequest;
import com.togethermarket.reservation.repository.*;
import com.togethermarket.reservation.service.ReservationService;
import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.*;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate",
      "reservation.approval-window=PT30M"
    })
class ReservationDatabaseIntegrationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfig {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired ReservationService service;
  @Autowired UserRepository users;
  @Autowired StoreRepository stores;
  @Autowired StoreHourRepository hours;
  @Autowired ProductRepository products;
  @Autowired PurchaseRequestRepository requests;
  @Autowired RecommendationCandidateRepository candidates;
  @Autowired RecommendationItemRepository items;
  @Autowired ReservationRepository reservations;
  @Autowired StoreOrderRepository orders;
  @Autowired JdbcTemplate jdbc;

  record Fixture(Long customer, Product first, Product second, CreateReservationRequest input) {}

  Fixture fixture() {
    var customer =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "고객", UserRole.CUSTOMER));
    var owner =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "점주", UserRole.OWNER));
    var a = stores.saveAndFlush(new Store(owner.getId(), "꽃", "꽃", "주소A", null, null, "010"));
    var b = stores.saveAndFlush(new Store(owner.getId(), "케이크", "제과", "주소B", null, null, "010"));
    var pickup = LocalDateTime.of(2026, 11, 20, 15, 0);
    for (var store : List.of(a, b))
      for (var type : HourType.values())
        hours.saveAndFlush(
            new StoreHour(
                store.getId(),
                null,
                pickup.toLocalDate(),
                type,
                false,
                LocalTime.of(9, 0),
                LocalTime.of(22, 0),
                (short) 0));
    var p1 =
        products.saveAndFlush(
            new Product(
                a.getId(), "꽃상품", "설명", null, new BigDecimal("30000"), 60, SaleStatus.ON_SALE));
    var p2 =
        products.saveAndFlush(
            new Product(
                b.getId(), "케이크상품", "설명", null, new BigDecimal("25000"), 120, SaleStatus.ON_SALE));
    var purchase =
        requests.saveAndFlush(
            new PurchaseRequest(
                customer.getId(),
                11L,
                new BigDecimal("60000"),
                pickup,
                RecommendationStatus.READY,
                null,
                pickup.minusHours(5),
                "{}"));
    var candidate = candidates.saveAndFlush(new RecommendationCandidate(purchase.getId(), 1, "추천"));
    items.saveAndFlush(
        new RecommendationItem(candidate.getId(), p1.getId(), 1, p1.getPriceAmount()));
    items.saveAndFlush(
        new RecommendationItem(candidate.getId(), p2.getId(), 1, p2.getPriceAmount()));
    var input =
        new CreateReservationRequest(
            candidate.getId().toString(),
            "고객",
            "010",
            null,
            List.of(
                new CreateReservationRequest.Pickup(
                    a.getId().toString(), pickup.atOffset(ZoneOffset.ofHours(9))),
                new CreateReservationRequest.Pickup(
                    b.getId().toString(), pickup.plusMinutes(15).atOffset(ZoneOffset.ofHours(9)))),
            new BigDecimal("55000"));
    return new Fixture(customer.getId(), p1, p2, input);
  }

  @Test
  void concurrentSameKeyCreatesOneReservationAndExactlyTwoOrders() throws Exception {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<ReservationService.Created> call =
          () -> {
            start.await();
            return service.create(f.customer(), key, f.input());
          };
      var first = pool.submit(call);
      var second = pool.submit(call);
      start.countDown();
      var a = first.get(30, TimeUnit.SECONDS);
      var b = second.get(30, TimeUnit.SECONDS);
      assertEquals(a.reservation().id(), b.reservation().id());
      assertNotEquals(a.replay(), b.replay());
      assertEquals(
          1,
          jdbc.queryForObject(
              "select count(*) from reservations where customer_id=?",
              Integer.class,
              f.customer()));
      assertEquals(
          2, orders.findByReservationIdOrderByIdAsc(Long.valueOf(a.reservation().id())).size());
    }
  }

  @Test
  void failureDuringSecondOrderRollsBackReservationAndFirstOrder() {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    String trigger = "test_res_fail_" + f.second().getId();
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE INSERT ON store_orders FOR EACH ROW BEGIN IF NEW.product_id = "
            + f.second().getId()
            + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test forced second order failure'; END IF; END");
    try {
      assertThrows(RuntimeException.class, () -> service.create(f.customer(), key, f.input()));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from reservations where customer_id=?",
              Integer.class,
              f.customer()));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from store_orders where product_id in (?,?)",
              Integer.class,
              f.first().getId(),
              f.second().getId()));
    } finally {
      jdbc.execute("DROP TRIGGER " + trigger);
    }
    assertFalse(service.create(f.customer(), key, f.input()).replay());
  }

  @Test
  void snapshotsSurviveProductChangeAndOwnershipIsEnforced() {
    var f = fixture();
    String key = UUID.randomUUID().toString();
    var created = service.create(f.customer(), key, f.input());
    Long id = Long.valueOf(created.reservation().id());
    f.first().updateDetails("변경상품", "변경설명", null, new BigDecimal("90000"), 60);
    products.saveAndFlush(f.first());
    var detail = service.detail(f.customer(), id);
    assertEquals(new BigDecimal("55000"), detail.totalAmount());
    assertEquals("꽃상품", detail.orders().getFirst().productNameSnapshot());
    assertEquals("2026-11-20T10:00+09:00", detail.createdAt().toString());
    assertThrows(
        com.togethermarket.global.api.ApiException.class,
        () -> service.detail(f.customer() + 999999, id));
    assertTrue(service.create(f.customer(), key, f.input()).replay());
  }

  @Test
  void expiredReservationsAreUpdatedBeforeStatusFiltering() {
    var f = fixture();
    var created = service.create(f.customer(), UUID.randomUUID().toString(), f.input());
    Long id = Long.valueOf(created.reservation().id());
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?", id);
    var pending =
        service.list(
            f.customer(), 1, 20, com.togethermarket.reservation.entity.ReservationStatus.PENDING);
    assertEquals(0, pending.totalCount());
    var failed =
        service.list(
            f.customer(), 1, 20, com.togethermarket.reservation.entity.ReservationStatus.FAILED);
    assertEquals(1, failed.totalCount());
    assertEquals("APPROVAL_EXPIRED", failed.items().getFirst().failureCode());
    assertTrue(
        failed.items().getFirst().orders().stream()
            .allMatch(o -> o.approvalStatus().equals("EXPIRED")));
  }
}
