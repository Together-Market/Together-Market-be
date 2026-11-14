package com.togethermarket.reservation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.*;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.reservation.dto.request.CreateReservationRequest;
import com.togethermarket.reservation.entity.*;
import com.togethermarket.reservation.repository.*;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationServiceTest {
  final UserRepository users = mock(UserRepository.class);
  final RecommendationCandidateRepository candidates =
      mock(RecommendationCandidateRepository.class);
  final PurchaseRequestRepository requests = mock(PurchaseRequestRepository.class);
  final RecommendationItemRepository items = mock(RecommendationItemRepository.class);
  final ProductRepository products = mock(ProductRepository.class);
  final StoreRepository stores = mock(StoreRepository.class);
  final ReservationRepository reservations = mock(ReservationRepository.class);
  final StoreOrderRepository orders = mock(StoreOrderRepository.class);
  final ProductAvailabilityService availability = mock(ProductAvailabilityService.class);
  final EntityManager em = mock(EntityManager.class);
  final Instant now = Instant.parse("2026-11-20T01:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final String key = "b492964a-c36b-4436-8a4e-d3e2e7cd138e";
  final List<StoreOrder> savedOrders = new ArrayList<>();
  Reservation saved;
  ReservationService service;
  Product p1, p2;
  PurchaseRequest purchase;

  static <T> T id(T t, long id) {
    ReflectionTestUtils.setField(t, "id", id);
    return t;
  }

  @BeforeEach
  void setup() {
    service =
        new ReservationService(
            users,
            candidates,
            requests,
            items,
            products,
            stores,
            reservations,
            orders,
            availability,
            new ReservationPolicy("PT30M"),
            clock,
            em);
    when(users.findForReservation(1L))
        .thenReturn(Optional.of(id(new User("a@test.com", "hash", "고객", UserRole.CUSTOMER), 1)));
    when(candidates.findById(200L))
        .thenReturn(Optional.of(id(new RecommendationCandidate(100L, 1, "추천"), 200)));
    purchase =
        id(
            new PurchaseRequest(
                1L,
                11L,
                new BigDecimal("60000"),
                LocalDateTime.of(2026, 11, 20, 15, 0),
                RecommendationStatus.READY,
                null,
                LocalDateTime.of(2026, 11, 20, 10, 0),
                "{}"),
            100);
    when(requests.findById(100L)).thenReturn(Optional.of(purchase));
    when(items.findByRecommendationCandidateIdOrderByProductIdAsc(200L))
        .thenReturn(
            List.of(
                new RecommendationItem(200L, 10L, 1, new BigDecimal("30000")),
                new RecommendationItem(200L, 20L, 1, new BigDecimal("25000"))));
    p1 = id(new Product(3L, "꽃", "설명", null, new BigDecimal("30000"), 60, SaleStatus.ON_SALE), 10);
    p2 =
        id(
            new Product(4L, "케이크", "설명", null, new BigDecimal("25000"), 120, SaleStatus.ON_SALE),
            20);
    when(products.findById(10L)).thenReturn(Optional.of(p1));
    when(products.findById(20L)).thenReturn(Optional.of(p2));
    for (long i = 3; i <= 4; i++) {
      var store =
          id(
              new Store(
                  9L,
                  "점포" + i,
                  "업종",
                  "주소" + i,
                  new BigDecimal(i == 3 ? "37.0000000" : "37.0010000"),
                  new BigDecimal("127.0000000"),
                  "010"),
              i);
      when(stores.findForReservation(i)).thenReturn(Optional.of(store));
      when(stores.findById(i)).thenReturn(Optional.of(store));
    }
    when(availability.check(anyLong(), any(), any())).thenReturn(new AvailabilityResult(List.of()));
    when(reservations.findByCustomerIdAndRequestKey(1L, key))
        .thenAnswer(a -> Optional.ofNullable(saved));
    when(reservations.saveAndFlush(any()))
        .thenAnswer(
            a -> {
              saved = id(a.getArgument(0), 500);
              return saved;
            });
    when(orders.save(any()))
        .thenAnswer(
            a -> {
              StoreOrder o = id(a.getArgument(0), 600 + savedOrders.size());
              savedOrders.add(o);
              return o;
            });
    when(orders.findByReservationIdOrderByIdAsc(500L)).thenAnswer(a -> savedOrders);
  }

  CreateReservationRequest input() {
    return new CreateReservationRequest(
        "200",
        "고객",
        "010-1234-5678",
        null,
        List.of(
            new CreateReservationRequest.Pickup(
                "3", OffsetDateTime.parse("2026-11-20T15:00:00+09:00")),
            new CreateReservationRequest.Pickup(
                "4", OffsetDateTime.parse("2026-11-20T15:15:00+09:00"))),
        new BigDecimal("55000"));
  }

  void error(String code, Runnable action) {
    assertEquals(code, assertThrows(ApiException.class, action::run).code());
  }

  @Test
  void savesTwoOrdersWithCurrentSnapshotsAndKst() {
    var result = service.create(1L, key, input());
    assertFalse(result.replay());
    assertEquals(2, savedOrders.size());
    assertEquals("500", result.reservation().id());
    assertEquals(new BigDecimal("55000"), result.reservation().totalAmount());
    assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), saved.getCreatedAt());
    assertEquals(LocalDateTime.of(2026, 11, 20, 10, 30), saved.getApprovalDeadlineAt());
    assertEquals(ZoneOffset.ofHours(9), result.reservation().createdAt().getOffset());
    assertEquals("주소3", savedOrders.getFirst().getPickupAddressSnapshot());
    assertEquals(64, saved.getRequestPayloadHash().length());
  }

  @Test
  void equivalentRetryUsesExistingReservationBeforeCatalogValidation() {
    service.create(1L, key, input());
    clearInvocations(products, availability, reservations);
    var original = input();
    var reversed =
        new CreateReservationRequest(
            "200",
            original.customerName(),
            original.customerPhone(),
            null,
            List.of(
                new CreateReservationRequest.Pickup(
                    "4", OffsetDateTime.parse("2026-11-20T06:15:00Z")),
                new CreateReservationRequest.Pickup(
                    "3", OffsetDateTime.parse("2026-11-20T06:00:00Z"))),
            new BigDecimal("55000.00"));
    var replay = service.create(1L, key, reversed);
    assertTrue(replay.replay());
    assertEquals(2, savedOrders.size());
    verifyNoInteractions(products, availability);
    verify(reservations, never()).saveAndFlush(any());
  }

  @Test
  void reusedKeyWithDifferentPayloadIsConflict() {
    service.create(1L, key, input());
    var i = input();
    error(
        "IDEMPOTENCY_KEY_REUSED",
        () ->
            service.create(
                1L,
                key,
                new CreateReservationRequest(
                    "200",
                    "다른 고객",
                    i.customerPhone(),
                    null,
                    i.pickups(),
                    i.expectedTotalAmount())));
    assertEquals(2, savedOrders.size());
  }

  @Test
  void anotherCustomersRecommendationIsHidden() {
    ReflectionTestUtils.setField(purchase, "customerId", 2L);
    error("NOT_FOUND", () -> service.create(1L, key, input()));
    verify(reservations, never()).saveAndFlush(any());
  }

  @Test
  void currentPriceAndBudgetAreRechecked() {
    ReflectionTestUtils.setField(p1, "priceAmount", new BigDecimal("31000"));
    error("PRICE_CHANGED", () -> service.create(1L, key, input()));
    ReflectionTestUtils.setField(p1, "priceAmount", new BigDecimal("30000"));
    ReflectionTestUtils.setField(purchase, "budgetAmount", new BigDecimal("54000"));
    error("BUDGET_EXCEEDED", () -> service.create(1L, key, input()));
    assertNull(saved);
  }

  @Test
  void refreshedPriceIsUsedInsteadOfEarlierManagedEntity() {
    doAnswer(
            a -> {
              ReflectionTestUtils.setField(p1, "priceAmount", new BigDecimal("32000"));
              return null;
            })
        .when(em)
        .refresh(eq(p1), any(jakarta.persistence.LockModeType.class));
    error("PRICE_CHANGED", () -> service.create(1L, key, input()));
    assertNull(saved);
  }

  @Test
  void unavailableProductDoesNotPersistAnything() {
    when(availability.check(eq(10L), any(), any()))
        .thenReturn(new AvailabilityResult(List.of(AvailabilityReason.PRODUCT_NOT_ON_SALE)));
    error("CANDIDATE_UNAVAILABLE", () -> service.create(1L, key, input()));
    verify(reservations, never()).saveAndFlush(any());
  }

  @ParameterizedTest
  @CsvSource({"37.0000000", "37.0044966"})
  void sameLocationAndJustInside500MetersCanReserve(String latitude) {
    var first = stores.findById(3L).orElseThrow();
    var store = stores.findById(4L).orElseThrow();
    ReflectionTestUtils.setField(store, "latitude", new BigDecimal(latitude));
    assertFalse(service.create(1L, key, input()).replay());
    assertEquals(2, savedOrders.size());
    var sequence = inOrder(stores, em, reservations);
    sequence.verify(stores).findForReservation(3L);
    sequence.verify(em).refresh(first, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    sequence.verify(stores).findForReservation(4L);
    sequence.verify(em).refresh(store, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    sequence.verify(reservations).saveAndFlush(any());
  }

  @Test
  void distanceThatRoundsTo500ButExceedsItDoesNotCreateReservationOrOrders() {
    var store = stores.findById(4L).orElseThrow();
    ReflectionTestUtils.setField(store, "latitude", new BigDecimal("37.0044967"));
    assertEquals(
        500L,
        com.togethermarket.recommendation.service.StoreDistanceCalculator.straightLineMeters(
                37.0, 127.0, 37.0044967, 127.0)
            .orElseThrow());
    var exception = assertThrows(ApiException.class, () -> service.create(1L, key, input()));
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.status());
    assertEquals("STORE_DISTANCE_EXCEEDED_OR_UNKNOWN", exception.code());
    verify(reservations, never()).saveAndFlush(any());
    verifyNoInteractions(orders, availability);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          NULL,127,37.001,127
          37,NULL,37.001,127
          37,127,NULL,127
          37,127,37.001,NULL
          NULL,NULL,37.001,127
          37,127,NULL,NULL
          90.0000001,127,37.001,127
          37,-180.0000001,37.001,127
          37,127,-90.0000001,127
          37,127,37.001,180.0000001
          """)
  void missingOrInvalidCurrentCoordinatePairsCannotReserve(
      BigDecimal latitudeA, BigDecimal longitudeA, BigDecimal latitudeB, BigDecimal longitudeB) {
    var a = stores.findById(3L).orElseThrow();
    var b = stores.findById(4L).orElseThrow();
    ReflectionTestUtils.setField(a, "latitude", latitudeA);
    ReflectionTestUtils.setField(a, "longitude", longitudeA);
    ReflectionTestUtils.setField(b, "latitude", latitudeB);
    ReflectionTestUtils.setField(b, "longitude", longitudeB);
    error("STORE_DISTANCE_EXCEEDED_OR_UNKNOWN", () -> service.create(1L, key, input()));
    verify(reservations, never()).saveAndFlush(any());
    verifyNoInteractions(orders, availability);
  }

  @Test
  void refreshedStoreCoordinatesAreUsedInsteadOfEarlierManagedCoordinates() {
    var b = stores.findById(4L).orElseThrow();
    doAnswer(
            invocation -> {
              ReflectionTestUtils.setField(b, "latitude", new BigDecimal("37.0100000"));
              return null;
            })
        .when(em)
        .refresh(b, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    error("STORE_DISTANCE_EXCEEDED_OR_UNKNOWN", () -> service.create(1L, key, input()));
    verify(reservations, never()).saveAndFlush(any());
    verifyNoInteractions(orders);
  }

  @Test
  void savedRetrySurvivesCoordinateChangesButANewKeyIsRevalidated() {
    var original = service.create(1L, key, input());
    ReflectionTestUtils.setField(stores.findById(3L).orElseThrow(), "latitude", null);
    clearInvocations(stores, em, reservations, orders, availability);
    var retry = service.create(1L, key, input());
    assertTrue(retry.replay());
    assertEquals(original.reservation().id(), retry.reservation().id());
    verify(stores, never()).findForReservation(anyLong());
    verifyNoInteractions(em, availability);
    verify(reservations, never()).saveAndFlush(any());
    verify(orders, never()).save(any());
    error(
        "STORE_DISTANCE_EXCEEDED_OR_UNKNOWN",
        () -> service.create(1L, "d8d521bf-43db-4c0c-a8a1-9569eb5dbe37", input()));
    assertEquals(2, savedOrders.size());
    verify(reservations, never()).saveAndFlush(any());
  }

  @Test
  void twoProductsFromSameStoreCannotReserve() {
    ReflectionTestUtils.setField(p2, "storeId", 3L);
    error("VALIDATION_ERROR", () -> service.create(1L, key, input()));
    verify(reservations, never()).saveAndFlush(any());
  }

  @Test
  void unreadyRecommendationAndInvalidKeyAreRejected() {
    ReflectionTestUtils.setField(purchase, "status", RecommendationStatus.RECOMMENDING);
    error("RECOMMENDATION_NOT_READY", () -> service.create(1L, key, input()));
    error("VALIDATION_ERROR", () -> service.create(1L, "invalid", input()));
  }

  @Test
  void exactDeadlineExpiresPendingOrdersButRetainsPriorApproval() {
    service.create(1L, key, input());
    ReflectionTestUtils.setField(
        saved, "approvalDeadlineAt", LocalDateTime.of(2026, 11, 20, 10, 0));
    ReflectionTestUtils.setField(savedOrders.getFirst(), "approvalStatus", ApprovalStatus.APPROVED);
    when(reservations.findByIdAndCustomerId(500L, 1L)).thenReturn(Optional.of(saved));
    var response = service.detail(1L, 500L);
    assertEquals("FAILED", response.status());
    assertEquals("APPROVAL_EXPIRED", response.failureCode());
    assertEquals(ApprovalStatus.APPROVED, savedOrders.getFirst().getApprovalStatus());
    assertEquals(ApprovalStatus.EXPIRED, savedOrders.getLast().getApprovalStatus());
  }

  @Test
  void unknownOrOtherCustomersReservationIsHidden() {
    error("NOT_FOUND", () -> service.detail(2L, 500L));
  }

  @Test
  void policyRequiresExplicitConfigurationAndCapsAtPreparationStart() {
    error(
        "RESERVATION_POLICY_NOT_CONFIGURED",
        () -> new ReservationPolicy("").deadline(now, now.plusSeconds(3600)));
    assertEquals(
        now.plusSeconds(600), new ReservationPolicy("PT30M").deadline(now, now.plusSeconds(600)));
    error("APPROVAL_WINDOW_UNAVAILABLE", () -> new ReservationPolicy("PT30M").deadline(now, now));
  }
}
