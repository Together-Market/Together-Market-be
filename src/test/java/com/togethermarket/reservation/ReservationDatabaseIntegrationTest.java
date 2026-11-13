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
  @Autowired com.togethermarket.reservation.service.OwnerOrderService ownerOrders;
  @Autowired com.togethermarket.reservation.service.ReservationExpiryWorker expiry;

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
    var ownerB =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "점주B", UserRole.OWNER));
    var b = stores.saveAndFlush(new Store(ownerB.getId(), "케이크", "제과", "주소B", null, null, "010"));
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

  record Decisions(
      Fixture fixture,
      Long reservation,
      Long firstOrder,
      Long secondOrder,
      Long firstStore,
      Long secondStore,
      Long firstOwner,
      Long secondOwner) {}

  Decisions decisions() {
    var f = fixture();
    var created = service.create(f.customer(), UUID.randomUUID().toString(), f.input());
    Long id = Long.valueOf(created.reservation().id());
    var list = orders.findByReservationIdOrderByIdAsc(id);
    var a = list.getFirst();
    var b = list.getLast();
    return new Decisions(
        f,
        id,
        a.getId(),
        b.getId(),
        a.getStoreId(),
        b.getStoreId(),
        stores.findById(a.getStoreId()).orElseThrow().getOwnerId(),
        stores.findById(b.getStoreId()).orElseThrow().getOwnerId());
  }

  @Test
  void concurrentApprovalsFromTwoOwnersConfirmExactlyOnce() throws Exception {
    var d = decisions();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return ownerOrders.approve(d.secondOwner(), d.secondStore(), d.secondOrder());
              });
      start.countDown();
      var ra = a.get(30, TimeUnit.SECONDS);
      var rb = b.get(30, TimeUnit.SECONDS);
      assertEquals(
          Set.of("PENDING", "CONFIRMED"), Set.of(ra.reservationStatus(), rb.reservationStatus()));
    }
    assertEquals(
        com.togethermarket.reservation.entity.ReservationStatus.CONFIRMED,
        reservations.findById(d.reservation()).orElseThrow().getStatus());
    assertTrue(
        orders.findByReservationIdOrderByIdAsc(d.reservation()).stream()
            .allMatch(
                o ->
                    o.getApprovalStatus()
                        == com.togethermarket.reservation.entity.ApprovalStatus.APPROVED));
    assertEquals(
        "CONFIRMED",
        ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder()).reservationStatus());
  }

  @Test
  void concurrentOppositeActionsOnSameOrderHaveOnlyOneWinner() throws Exception {
    var d = decisions();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                try {
                  ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
                  return "APPROVED";
                } catch (com.togethermarket.global.api.ApiException e) {
                  return e.code();
                }
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                try {
                  ownerOrders.reject(d.firstOwner(), d.firstStore(), d.firstOrder(), "재고 없음");
                  return "REJECTED";
                } catch (com.togethermarket.global.api.ApiException e) {
                  return e.code();
                }
              });
      start.countDown();
      var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
      assertEquals(
          1, results.stream().filter(x -> x.equals("APPROVED") || x.equals("REJECTED")).count());
      var saved = orders.findById(d.firstOrder()).orElseThrow();
      assertTrue(results.contains(saved.getApprovalStatus().name()));
    }
  }

  @Test
  void lateDecisionCommitsExpiryEvenThoughItReturnsConflict() {
    var d = decisions();
    ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?",
        d.reservation());
    assertThrows(
        com.togethermarket.reservation.service.ReservationExpiredException.class,
        () -> ownerOrders.approve(d.secondOwner(), d.secondStore(), d.secondOrder()));
    var saved = reservations.findById(d.reservation()).orElseThrow();
    assertEquals("APPROVAL_EXPIRED", saved.getFailureCode());
    assertEquals(com.togethermarket.reservation.entity.ReservationStatus.FAILED, saved.getStatus());
    assertEquals(
        com.togethermarket.reservation.entity.ApprovalStatus.APPROVED,
        orders.findById(d.firstOrder()).orElseThrow().getApprovalStatus());
    assertEquals(
        com.togethermarket.reservation.entity.ApprovalStatus.EXPIRED,
        orders.findById(d.secondOrder()).orElseThrow().getApprovalStatus());
  }

  @Test
  void approvalRacingExpiryWorkerCannotConfirmAnExpiredReservation() throws Exception {
    var d = decisions();
    ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?",
        d.reservation());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var approval =
          pool.submit(
              () -> {
                start.await();
                try {
                  ownerOrders.approve(d.secondOwner(), d.secondStore(), d.secondOrder());
                  return "UNEXPECTED_APPROVAL";
                } catch (com.togethermarket.global.api.ApiException e) {
                  return e.code();
                }
              });
      var timeout =
          pool.submit(
              () -> {
                start.await();
                return expiry.expire(d.reservation());
              });
      start.countDown();
      assertEquals("RESERVATION_EXPIRED", approval.get(30, TimeUnit.SECONDS));
      timeout.get(30, TimeUnit.SECONDS);
    }
    assertEquals(
        com.togethermarket.reservation.entity.ReservationStatus.FAILED,
        reservations.findById(d.reservation()).orElseThrow().getStatus());
    assertEquals(
        com.togethermarket.reservation.entity.ApprovalStatus.EXPIRED,
        orders.findById(d.secondOrder()).orElseThrow().getApprovalStatus());
  }

  @Test
  void ownerListHasOnlyOwnStoreAndExpiresBeforeBothFilters() {
    var d = decisions();
    assertEquals(
        "NOT_FOUND",
        assertThrows(
                com.togethermarket.global.api.ApiException.class,
                () -> ownerOrders.detail(d.firstOwner(), d.secondStore(), d.secondOrder()))
            .code());
    assertEquals(
        "NOT_FOUND",
        assertThrows(
                com.togethermarket.global.api.ApiException.class,
                () -> ownerOrders.detail(d.firstOwner(), d.firstStore(), d.secondOrder()))
            .code());
    var before =
        ownerOrders.list(
            d.firstOwner(),
            d.firstStore(),
            1,
            20,
            com.togethermarket.reservation.entity.ApprovalStatus.PENDING,
            com.togethermarket.reservation.entity.ReservationStatus.PENDING);
    assertEquals(1, before.totalCount());
    assertEquals(d.firstOrder().toString(), before.items().getFirst().id());
    assertTrue(before.items().getFirst().canRespond());
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?",
        d.reservation());
    assertEquals(
        0,
        ownerOrders
            .list(
                d.firstOwner(),
                d.firstStore(),
                1,
                20,
                com.togethermarket.reservation.entity.ApprovalStatus.PENDING,
                com.togethermarket.reservation.entity.ReservationStatus.PENDING)
            .totalCount());
    var after =
        ownerOrders.list(
            d.firstOwner(),
            d.firstStore(),
            1,
            20,
            com.togethermarket.reservation.entity.ApprovalStatus.EXPIRED,
            com.togethermarket.reservation.entity.ReservationStatus.FAILED);
    assertEquals(1, after.totalCount());
    assertFalse(after.items().getFirst().canRespond());
  }

  @Test
  void expiryWorkerIsIdempotentAndNeverChangesConfirmedReservations() {
    var d = decisions();
    assertFalse(expiry.expire(d.reservation()));
    ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
    ownerOrders.approve(d.secondOwner(), d.secondStore(), d.secondOrder());
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?",
        d.reservation());
    assertFalse(expiry.expire(d.reservation()));
    assertEquals(
        com.togethermarket.reservation.entity.ReservationStatus.CONFIRMED,
        reservations.findById(d.reservation()).orElseThrow().getStatus());
    var pending = decisions();
    jdbc.update(
        "update reservations set approval_deadline_at='2026-11-20 10:00:00' where id=?",
        pending.reservation());
    assertTrue(expiry.expire(pending.reservation()));
    assertFalse(expiry.expire(pending.reservation()));
  }

  @Test
  void oneStoreRejectionFailsWholeReservationAndPreservesOtherApproval() {
    var d = decisions();
    ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder());
    var response = ownerOrders.reject(d.secondOwner(), d.secondStore(), d.secondOrder(), "준비 불가");
    assertEquals("FAILED", response.reservationStatus());
    assertEquals("STORE_REJECTED", response.failureCode());
    assertFalse(response.canRespond());
    assertEquals(
        "FAILED",
        ownerOrders.approve(d.firstOwner(), d.firstStore(), d.firstOrder()).reservationStatus());
    assertEquals(
        "준비 불가",
        ownerOrders
            .reject(d.secondOwner(), d.secondStore(), d.secondOrder(), "준비 불가")
            .rejectionReason());
    assertThrows(
        com.togethermarket.global.api.ApiException.class,
        () -> ownerOrders.reject(d.secondOwner(), d.secondStore(), d.secondOrder(), "다른 사유"));
    assertEquals(
        "STORE_REJECTED", service.detail(d.fixture().customer(), d.reservation()).failureCode());
  }
}
