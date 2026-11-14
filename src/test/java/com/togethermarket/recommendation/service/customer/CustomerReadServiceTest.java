package com.togethermarket.recommendation.service.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerReadServiceTest {
  final ProductRepository products = mock(ProductRepository.class);
  final ProductPurposeTagRepository tags = mock(ProductPurposeTagRepository.class);
  final StoreRepository stores = mock(StoreRepository.class);
  final StoreHourRepository hours = mock(StoreHourRepository.class);
  final RecommendationCandidateRepository candidates =
      mock(RecommendationCandidateRepository.class);
  final RecommendationItemRepository items = mock(RecommendationItemRepository.class);
  final PurchaseRequestRepository purchases = mock(PurchaseRequestRepository.class);
  final CustomerReadService service = service(Set.of(SaleStatus.values()), false);

  CustomerReadService service(Set<SaleStatus> statuses, boolean coordinatesRequired) {
    return new CustomerReadService(
        products,
        tags,
        stores,
        hours,
        candidates,
        items,
        purchases,
        new CustomerReadPolicy(statuses, coordinatesRequired));
  }

  static <T> T id(T entity, long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    return entity;
  }

  static Product product(long id, long storeId, SaleStatus status) {
    return id(new Product(storeId, "상품", "설명", null, BigDecimal.ZERO, 0, status), id);
  }

  static Store store(long id, BigDecimal latitude, BigDecimal longitude) {
    return id(new Store(999L, "점포", "꽃집", "용인시", latitude, longitude, null), id);
  }

  static void fails(String code, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  void ownership(long customerId) {
    when(candidates.findById(5L)).thenReturn(Optional.of(new RecommendationCandidate(7L, 1, "추천")));
    var purchase = mock(PurchaseRequest.class);
    when(purchase.getCustomerId()).thenReturn(customerId);
    when(purchases.findById(7L)).thenReturn(Optional.of(purchase));
  }

  void pair() {
    ownership(2L);
    when(items.findByRecommendationCandidateIdInOrderByIdAsc(List.of(5L)))
        .thenReturn(
            List.of(
                new RecommendationItem(5L, 11L, 1, BigDecimal.TEN),
                new RecommendationItem(5L, 12L, 1, BigDecimal.TEN)));
    when(products.findAllById(Set.of(11L, 12L)))
        .thenReturn(
            List.of(product(12, 22, SaleStatus.PAUSED), product(11, 21, SaleStatus.SOLD_OUT)));
    when(stores.findAllById(Set.of(21L, 22L)))
        .thenReturn(
            List.of(
                store(22, BigDecimal.ZERO, BigDecimal.ONE),
                store(21, BigDecimal.ZERO, BigDecimal.ZERO)));
  }

  @Test
  void unauthenticatedCallsNeverReadData() {
    fails("UNAUTHORIZED", () -> service.product("11", null));
    fails("UNAUTHORIZED", () -> service.store("21", 0L));
    fails("UNAUTHORIZED", () -> service.locations("5", -1L));
    verifyNoInteractions(products, tags, stores, hours, candidates, items, purchases);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
  void invalidIdsNeverReadData(String rawId) {
    fails("VALIDATION_ERROR", () -> service.product(rawId, 2L));
    fails("VALIDATION_ERROR", () -> service.store(rawId, 2L));
    fails("VALIDATION_ERROR", () -> service.locations(rawId, 2L));
    verifyNoInteractions(products, tags, stores, hours, candidates, items, purchases);
  }

  @Test
  void currentProductStatePriceAndSortedCategoriesAreReturned() {
    when(products.findById(11L)).thenReturn(Optional.of(product(11, 21, SaleStatus.PAUSED)));
    when(stores.findById(21L)).thenReturn(Optional.of(store(21, null, null)));
    when(tags.findByIdProductId(11L))
        .thenReturn(List.of(new ProductPurposeTag(11L, 12L), new ProductPurposeTag(11L, 3L)));
    var result = service.product("11", 2L);
    assertThat(result.saleStatus()).isEqualTo(SaleStatus.PAUSED);
    assertThat(result.priceAmount()).isZero();
    assertThat(result.purposeCategoryIds()).containsExactly("3", "12");
    assertThat(result.store().latitude()).isNull();
    fails("PRODUCT_NOT_FOUND", () -> service(Set.of(SaleStatus.ON_SALE), false).product("11", 2L));
  }

  @Test
  void missingEntitiesReturnDomainNotFound() {
    fails("PRODUCT_NOT_FOUND", () -> service.product("11", 2L));
    fails("STORE_NOT_FOUND", () -> service.store("21", 2L));
    fails("RECOMMENDATION_NOT_FOUND", () -> service.locations("5", 2L));
    when(products.findById(11L)).thenReturn(Optional.of(product(11, 21, SaleStatus.ON_SALE)));
    fails("PRODUCT_NOT_FOUND", () -> service.product("11", 2L));
  }

  @Test
  void schedulesPreserveOvernightAndDatedClosureRatherThanComputeAvailability() {
    when(stores.findById(21L)).thenReturn(Optional.of(store(21, BigDecimal.ZERO, BigDecimal.ZERO)));
    var overnight =
        id(
            new StoreHour(
                21L,
                (short) 1,
                null,
                HourType.PICKUP,
                false,
                LocalTime.of(22, 0),
                LocalTime.of(1, 0),
                (short) 1),
            1);
    var closure =
        id(
            new StoreHour(
                21L,
                null,
                LocalDate.of(2026, 11, 14),
                HourType.BUSINESS,
                true,
                null,
                null,
                (short) 0),
            2);
    when(hours.findByStoreId(21L)).thenReturn(List.of(closure, overnight));
    var result = service.store("21", 2L);
    assertThat(result.hours().getFirst().endDayOffset()).isEqualTo((short) 1);
    assertThat(result.hours().getFirst().closesAt()).isEqualTo(LocalTime.of(1, 0));
    assertThat(result.hours().get(1).closed()).isTrue();
    assertThat(result.hours().get(1).opensAt()).isNull();
  }

  @Test
  void detailCoordinatePolicyIsExplicitAndNeverReturnsHalfAPair() {
    when(stores.findById(21L)).thenReturn(Optional.of(store(21, BigDecimal.ONE, null)));
    var result = service.store("21", 2L);
    assertThat(result.latitude()).isNull();
    assertThat(result.longitude()).isNull();
    assertThat(result.hours()).isEmpty();
    fails(
        "STORE_LOCATION_UNAVAILABLE",
        () -> service(Set.of(SaleStatus.values()), true).store("21", 2L));
  }

  @Test
  void ownershipIsCheckedBeforeAnyLocationLookup() {
    ownership(3L);
    fails("FORBIDDEN", () -> service.locations("5", 2L));
    verifyNoInteractions(products, stores, items, tags, hours);
  }

  @Test
  void locationsPreserveItemOrderAndUseStraightLineDistanceDespiteSaleChanges() {
    pair();
    var result = service.locations("5", 2L);
    assertThat(result.stores()).extracting(s -> s.id()).containsExactly("21", "22");
    assertThat(result.distanceType()).isEqualTo("STRAIGHT_LINE");
    assertThat(result.distanceMeters()).isEqualTo(111195);
    verifyNoInteractions(tags, hours);
  }

  @Test
  void missingOrInvalidMapCoordinatesAreNotFabricated() {
    pair();
    for (BigDecimal latitude : Arrays.asList(null, new BigDecimal("90.01"))) {
      when(stores.findAllById(any()))
          .thenReturn(
              List.of(
                  store(21, latitude, BigDecimal.ZERO),
                  store(22, BigDecimal.ZERO, BigDecimal.ZERO)));
      fails("STORE_LOCATION_UNAVAILABLE", () -> service.locations("5", 2L));
    }
  }

  @Test
  void twoDifferentStoresAtSameLocationHaveLegitimateZeroDistance() {
    pair();
    when(stores.findAllById(any()))
        .thenReturn(
            List.of(
                store(21, BigDecimal.ZERO, BigDecimal.ZERO),
                store(22, BigDecimal.ZERO, BigDecimal.ZERO)));
    assertThat(service.locations("5", 2L).distanceMeters()).isZero();
  }

  @Test
  void incompleteOrDuplicateCompositionIsRejected() {
    pair();
    var item = new RecommendationItem(5L, 11L, 1, BigDecimal.TEN);
    for (var invalid :
        List.of(
            List.<RecommendationItem>of(),
            List.of(item),
            List.of(item, item),
            List.of(item, new RecommendationItem(6L, 12L, 1, BigDecimal.TEN)))) {
      when(items.findByRecommendationCandidateIdInOrderByIdAsc(any())).thenReturn(invalid);
      fails("RECOMMENDATION_DATA_UNAVAILABLE", () -> service.locations("5", 2L));
    }
    verify(products, never()).findAllById(any());
  }

  @Test
  void missingProductsStoresAndSameStorePairsAreRejected() {
    pair();
    when(products.findAllById(any())).thenReturn(List.of(product(11, 21, SaleStatus.ON_SALE)));
    fails("RECOMMENDATION_DATA_UNAVAILABLE", () -> service.locations("5", 2L));
    when(products.findAllById(any()))
        .thenReturn(
            List.of(product(11, 21, SaleStatus.ON_SALE), product(12, 21, SaleStatus.ON_SALE)));
    fails("RECOMMENDATION_DATA_UNAVAILABLE", () -> service.locations("5", 2L));
    when(products.findAllById(any()))
        .thenReturn(
            List.of(product(11, 21, SaleStatus.ON_SALE), product(12, 22, SaleStatus.ON_SALE)));
    when(stores.findAllById(any()))
        .thenReturn(List.of(store(21, BigDecimal.ZERO, BigDecimal.ZERO)));
    fails("RECOMMENDATION_DATA_UNAVAILABLE", () -> service.locations("5", 2L));
  }
}
