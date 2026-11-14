package com.togethermarket.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.repository.*;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationClockCoexistenceTest {
  static <T> T id(T entity, long value) {
    ReflectionTestUtils.setField(entity, "id", value);
    return entity;
  }

  @Test
  void allReservationConsumersUseTheirClockWhenBe3ClockAlsoExists() {
    var reservations = mock(ReservationRepository.class);
    var orders = mock(StoreOrderRepository.class);
    var stores = mock(StoreRepository.class);
    var users = mock(UserRepository.class);
    var reservation =
        id(
            new Reservation(
                1L,
                200L,
                ReservationStatus.PENDING,
                null,
                "고객",
                "010",
                null,
                LocalDateTime.of(2026, 11, 20, 10, 30),
                "key",
                "hash",
                LocalDateTime.of(2026, 11, 20, 10, 0)),
            500);
    var order =
        id(
            new StoreOrder(
                500L,
                3L,
                10L,
                1,
                BigDecimal.ZERO,
                "상품",
                "주소",
                LocalDateTime.of(2026, 11, 20, 15, 0),
                ApprovalStatus.PENDING,
                null,
                null),
            600);
    var store = id(new Store(9L, "점포", "업종", "주소", null, null, "010"), 3);
    when(reservations.findByIdAndCustomerId(500L, 1L)).thenReturn(Optional.of(reservation));
    when(reservations.findForUpdate(500L)).thenReturn(Optional.of(reservation));
    when(orders.findReservationId(600L, 3L)).thenReturn(Optional.of(500L));
    when(orders.findByReservationIdOrderByIdAsc(500L)).thenReturn(List.of(order));
    when(stores.findById(3L)).thenReturn(Optional.of(store));
    when(stores.findByIdAndOwnerId(3L, 9L)).thenReturn(Optional.of(store));
    when(users.findById(9L))
        .thenReturn(Optional.of(new User("owner@test.com", "hash", "점주", UserRole.OWNER)));
    new ApplicationContextRunner()
        .withUserConfiguration(
            ReservationService.class,
            OwnerOrderService.class,
            ReservationExpiryWorker.class,
            ReservationExpiryScheduler.class)
        .withPropertyValues("reservation.expiry-scheduler.delay=PT1H")
        .withBean(
            "reservationClock",
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneOffset.UTC))
        // Same bean name as BE3's opt-in configuration, with a distinct time to detect wrong
        // injection.
        .withBean(
            "recommendationClock",
            Clock.class,
            () -> Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC))
        .withBean(ReservationRepository.class, () -> reservations)
        .withBean(StoreOrderRepository.class, () -> orders)
        .withBean(StoreRepository.class, () -> stores)
        .withBean(UserRepository.class, () -> users)
        .withBean(
            RecommendationCandidateRepository.class,
            () -> mock(RecommendationCandidateRepository.class))
        .withBean(PurchaseRequestRepository.class, () -> mock(PurchaseRequestRepository.class))
        .withBean(
            RecommendationItemRepository.class, () -> mock(RecommendationItemRepository.class))
        .withBean(ProductRepository.class, () -> mock(ProductRepository.class))
        .withBean(ProductAvailabilityService.class, () -> mock(ProductAvailabilityService.class))
        .withBean(ReservationPolicy.class, () -> new ReservationPolicy("PT30M"))
        .withBean(EntityManager.class, () -> mock(EntityManager.class))
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBeansOfType(Clock.class)).hasSize(2);
              assertThat(c.getBean(ReservationService.class).detail(1L, 500L).status())
                  .isEqualTo("PENDING");
              assertThat(c.getBean(OwnerOrderService.class).detail(9L, 3L, 600L).canRespond())
                  .isTrue();
              assertThat(c.getBean(ReservationExpiryWorker.class).expire(500L)).isFalse();
              c.getBean(ReservationExpiryScheduler.class).sweep();
              verify(reservations).findDueIds(eq(LocalDateTime.of(2026, 11, 20, 10, 0)), any());
            });
  }
}
