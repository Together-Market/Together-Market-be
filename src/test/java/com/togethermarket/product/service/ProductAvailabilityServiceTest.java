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
}
