package com.togethermarket.product.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.entity.Product;
import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.StoreHour;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductAvailabilityServiceTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final StoreRepository stores = mock(StoreRepository.class);
  private final StoreHourRepository hours = mock(StoreHourRepository.class);
  private final ProductAvailabilityService service =
      new DefaultProductAvailabilityService(products, stores, hours, new StoreScheduleEvaluator());
  private final Instant pickup = Instant.parse("2026-11-16T03:00:00Z"); // Monday noon KST

  private void arrange(SaleStatus status) {
    when(products.findById(3L))
        .thenReturn(
            Optional.of(
                new Product(1L, "flower", "gift", null, BigDecimal.valueOf(10000), 60, status)));
    when(stores.existsById(1L)).thenReturn(true);
    when(hours.findByStoreId(1L))
        .thenReturn(
            List.of(
                new StoreHour(
                    1L,
                    (short) 1,
                    null,
                    HourType.BUSINESS,
                    false,
                    LocalTime.of(9, 0),
                    LocalTime.of(18, 0),
                    (short) 0),
                new StoreHour(
                    1L,
                    (short) 1,
                    null,
                    HourType.PICKUP,
                    false,
                    LocalTime.of(11, 0),
                    LocalTime.of(16, 0),
                    (short) 0)));
  }

  @Test
  void preparationBoundaryAndKoreanSchedule() {
    arrange(SaleStatus.ON_SALE);
    assertTrue(service.check(3L, pickup, pickup.minusSeconds(3600)).available());
    assertEquals(
        List.of(AvailabilityReason.PREPARATION_TIME_INSUFFICIENT),
        service.check(3L, pickup, pickup.minusSeconds(3599)).reasons());
  }

  @Test
  void soldOutAndPastPickupCannotPass() {
    arrange(SaleStatus.SOLD_OUT);
    var result = service.check(3L, pickup, pickup.plusSeconds(1));
    assertFalse(result.available());
    assertTrue(result.reasons().contains(AvailabilityReason.PRODUCT_NOT_ON_SALE));
    assertTrue(result.reasons().contains(AvailabilityReason.PICKUP_NOT_IN_FUTURE));
  }

  @Test
  void unknownProductDoesNotQuerySchedules() {
    when(products.findById(3L)).thenReturn(Optional.empty());
    assertEquals(
        List.of(AvailabilityReason.PRODUCT_NOT_FOUND), service.check(3L, pickup, pickup).reasons());
    verifyNoInteractions(stores, hours);
  }

  @Test
  void bulkChecksUseIdenticalRulesAndBatchQueriesIncludingMissingRows() {
    for (var status : SaleStatus.values()) {
      arrange(status);
      var product = products.findById(3L).orElseThrow();
      org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 3L);
      var store =
          new com.togethermarket.store.entity.Store(1L, "점포", "카페", "주소", null, null, "010");
      org.springframework.test.util.ReflectionTestUtils.setField(store, "id", 1L);
      when(products.findAllById(List.of(3L, 99L))).thenReturn(List.of(product));
      when(stores.findAllById(List.of(1L))).thenReturn(List.of(store));
      var schedules = hours.findByStoreId(1L);
      when(hours.findByStoreIdIn(List.of(1L))).thenReturn(schedules);
      for (var time : List.of(pickup.minusSeconds(3600), pickup.minusSeconds(3599), pickup)) {
        var expected = service.check(3L, pickup, time);
        var actual = service.checkAll(List.of(3L, 99L), pickup, time);
        assertEquals(expected, actual.get(3L));
        assertEquals(List.of(AvailabilityReason.PRODUCT_NOT_FOUND), actual.get(99L).reasons());
      }
    }
    when(stores.findAllById(List.of(1L))).thenReturn(List.of());
    assertEquals(
        List.of(AvailabilityReason.STORE_NOT_FOUND),
        service.checkAll(List.of(3L, 99L), pickup, pickup).get(3L).reasons());
  }
}
