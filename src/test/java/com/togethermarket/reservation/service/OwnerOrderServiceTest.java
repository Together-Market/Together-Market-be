package com.togethermarket.reservation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.reservation.entity.*;
import com.togethermarket.reservation.repository.*;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerOrderServiceTest {
  final ReservationRepository reservations = mock(ReservationRepository.class);
  final StoreOrderRepository orders = mock(StoreOrderRepository.class);
  final StoreRepository stores = mock(StoreRepository.class);
  final UserRepository users = mock(UserRepository.class);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneOffset.UTC);
  OwnerOrderService service = new OwnerOrderService(reservations, orders, stores, users, clock);
  Reservation reservation;
  StoreOrder first, second;

  static <T> T id(T entity, long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    return entity;
  }

  @BeforeEach
  void setup() {
    reservation =
        id(
            new Reservation(
                1L,
                200L,
                ReservationStatus.PENDING,
                null,
                "고객",
                "010",
                "요청",
                LocalDateTime.of(2026, 11, 20, 10, 30),
                "key",
                "hash",
                LocalDateTime.of(2026, 11, 20, 10, 0)),
            500);
    first =
        id(
            new StoreOrder(
                500L,
                3L,
                10L,
                1,
                new BigDecimal("30000"),
                "꽃",
                "주소3",
                LocalDateTime.of(2026, 11, 20, 15, 0),
                ApprovalStatus.PENDING,
                null,
                null),
            600);
    second =
        id(
            new StoreOrder(
                500L,
                4L,
                20L,
                1,
                new BigDecimal("25000"),
                "케이크",
                "주소4",
                LocalDateTime.of(2026, 11, 20, 15, 15),
                ApprovalStatus.PENDING,
                null,
                null),
            601);
    when(users.findById(9L))
        .thenReturn(Optional.of(id(new User("owner@test.com", "hash", "점주", UserRole.OWNER), 9)));
    for (long s = 3; s <= 4; s++)
      when(stores.findByIdAndOwnerId(s, 9L))
          .thenReturn(
              Optional.of(id(new Store(9L, "점포" + s, "업종", "주소" + s, null, null, "010"), s)));
    when(orders.findReservationId(600L, 3L)).thenReturn(Optional.of(500L));
    when(orders.findReservationId(601L, 4L)).thenReturn(Optional.of(500L));
    when(reservations.findForUpdate(500L)).thenReturn(Optional.of(reservation));
    when(orders.findByReservationIdOrderByIdAsc(500L)).thenReturn(List.of(first, second));
  }

  @Test
  void onlySecondApprovalConfirmsAndRetriesDoNotChangeResponseTime() {
    var a = service.approve(9L, 3L, 600L);
    assertEquals("PENDING", a.reservationStatus());
    assertEquals("APPROVED", a.approvalStatus());
    assertFalse(a.canRespond());
    assertTrue(service.detail(9L, 4L, 601L).canRespond());
    var b = service.approve(9L, 4L, 601L);
    assertEquals("CONFIRMED", b.reservationStatus());
    assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    assertFalse(b.canRespond());
    var time = first.getRespondedAt();
    assertEquals("CONFIRMED", service.approve(9L, 3L, 600L).reservationStatus());
    assertEquals(time, first.getRespondedAt());
  }

  @Test
  void rejectionFailsWholeReservationAndKeepsPriorApproval() {
    service.approve(9L, 3L, 600L);
    var rejected = service.reject(9L, 4L, 601L, "준비 불가");
    assertEquals("FAILED", rejected.reservationStatus());
    assertEquals("STORE_REJECTED", rejected.failureCode());
    assertEquals(ApprovalStatus.APPROVED, first.getApprovalStatus());
    assertFalse(service.detail(9L, 3L, 600L).canRespond());
    assertEquals("FAILED", service.reject(9L, 4L, 601L, "준비 불가").reservationStatus());
    assertEquals("준비 불가", second.getRejectionReason());
    assertEquals("FAILED", service.approve(9L, 3L, 600L).reservationStatus());
  }

  @Test
  void oppositeOrChangedResponseCannotOverwritePreviousDecision() {
    service.reject(9L, 3L, 600L, "재고 없음");
    assertThrows(ApiException.class, () -> service.approve(9L, 3L, 600L));
    assertThrows(ApiException.class, () -> service.reject(9L, 3L, 600L, "다른 사유"));
    assertEquals("재고 없음", first.getRejectionReason());
    assertEquals(ApprovalStatus.REJECTED, first.getApprovalStatus());
  }

  @Test
  void unrelatedStoreAndWrongOrderAreHiddenBeforeMutation() {
    assertEquals(
        "NOT_FOUND", assertThrows(ApiException.class, () -> service.detail(9L, 99L, 600L)).code());
    assertEquals(
        "NOT_FOUND", assertThrows(ApiException.class, () -> service.approve(9L, 3L, 601L)).code());
    verify(reservations, never()).findForUpdate(any());
    assertEquals(ApprovalStatus.PENDING, first.getApprovalStatus());
  }

  @Test
  void exactlyAtDeadlineLateDecisionExpiresBothOrders() {
    ReflectionTestUtils.setField(
        reservation, "approvalDeadlineAt", LocalDateTime.of(2026, 11, 20, 10, 0));
    assertThrows(ReservationExpiredException.class, () -> service.approve(9L, 3L, 600L));
    assertEquals("APPROVAL_EXPIRED", reservation.getFailureCode());
    assertEquals(ApprovalStatus.EXPIRED, first.getApprovalStatus());
    assertEquals(ApprovalStatus.EXPIRED, second.getApprovalStatus());
    assertNull(first.getRespondedAt());
  }

  @Test
  void lateOwnerReadExpiresRemainingOrderButPreservesApproval() {
    service.approve(9L, 3L, 600L);
    ReflectionTestUtils.setField(
        reservation, "approvalDeadlineAt", LocalDateTime.of(2026, 11, 20, 10, 0));
    var result = service.detail(9L, 4L, 601L);
    assertEquals("FAILED", result.reservationStatus());
    assertEquals("EXPIRED", result.approvalStatus());
    assertFalse(result.canRespond());
    assertEquals(ApprovalStatus.APPROVED, first.getApprovalStatus());
  }

  @Test
  void blankReasonAndInvalidPageAreRejected() {
    assertThrows(ApiException.class, () -> service.reject(9L, 3L, 600L, " "));
    assertThrows(ApiException.class, () -> service.list(9L, 3L, 0, 20, null, null));
    verifyNoInteractions(orders);
  }

  @Test
  void cannotConfirmMalformedSingleStoreOrder() {
    when(orders.findByReservationIdOrderByIdAsc(500L)).thenReturn(List.of(first));
    assertEquals(
        "INVALID_RESERVATION_STATE",
        assertThrows(ApiException.class, () -> service.approve(9L, 3L, 600L)).code());
    assertEquals(ApprovalStatus.PENDING, first.getApprovalStatus());
  }
}
