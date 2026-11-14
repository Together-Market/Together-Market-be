package com.togethermarket.recommendation.service.customer;

import com.togethermarket.product.repository.ProductPurposeTagRepository;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.recommendation.dto.response.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.StoreDistanceCalculator;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.recommendation.service.purchase.PurchaseRules;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.entity.StoreHour;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/** Read-only customer projections. The caller must supply a trusted CUSTOMER identity from BE1. */
@Transactional(readOnly = true)
public class CustomerReadService {
  private final ProductRepository products;
  private final ProductPurposeTagRepository tags;
  private final StoreRepository stores;
  private final StoreHourRepository hours;
  private final RecommendationCandidateRepository candidates;
  private final RecommendationItemRepository items;
  private final PurchaseRequestRepository purchases;
  private final CustomerReadPolicy policy;

  public CustomerReadService(
      ProductRepository products,
      ProductPurposeTagRepository tags,
      StoreRepository stores,
      StoreHourRepository hours,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      PurchaseRequestRepository purchases,
      CustomerReadPolicy policy) {
    this.products = products;
    this.tags = tags;
    this.stores = stores;
    this.hours = hours;
    this.candidates = candidates;
    this.items = items;
    this.purchases = purchases;
    this.policy = policy;
  }

  public CustomerProductResponse product(String rawId, Long customerId) {
    return product(rawId, customerId, true);
  }

  public CustomerProductResponse productForRecommendation(String rawId, Long customerId) {
    return product(rawId, customerId, false);
  }

  private CustomerProductResponse product(String rawId, Long customerId, boolean applyVisibility) {
    requireCustomer(customerId);
    Long id = PurchaseRules.parseId(rawId, "product_id");
    var product =
        products
            .findById(id)
            .filter(
                p -> !applyVisibility || policy.visibleSaleStatuses().contains(p.getSaleStatus()))
            .orElseThrow(() -> error("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
    var store =
        stores
            .findById(product.getStoreId())
            .orElseThrow(() -> error("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
    var summary = summary(store, policy.requireDetailCoordinates());
    var categoryIds =
        tags.findByIdProductId(id).stream()
            .map(t -> t.getId().getPurposeCategoryId())
            .distinct()
            .sorted()
            .map(Object::toString)
            .toList();
    return new CustomerProductResponse(
        product.getId().toString(),
        store.getId().toString(),
        product.getName(),
        product.getDescription(),
        product.getImageUrl(),
        product.getPriceAmount().longValueExact(),
        product.getPreparationMinutes(),
        product.getSaleStatus(),
        categoryIds,
        summary);
  }

  public CustomerStoreResponse store(String rawId, Long customerId) {
    requireCustomer(customerId);
    Long id = PurchaseRules.parseId(rawId, "store_id");
    var store = stores.findById(id).orElseThrow(() -> error("STORE_NOT_FOUND", "점포를 찾을 수 없습니다."));
    var summary = summary(store, policy.requireDetailCoordinates());
    // Match BE2's stable schedule ordering; calendars are displayed, never reinterpreted here.
    var schedule =
        hours.findByStoreId(id).stream()
            .sorted(
                Comparator.comparing(
                        StoreHour::getTargetDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StoreHour::getWeekday, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(h -> h.getHourType().name())
                    .thenComparing(StoreHour::getId))
            .map(
                h ->
                    new CustomerStoreResponse.Hour(
                        h.getWeekday(),
                        h.getTargetDate(),
                        h.getHourType(),
                        h.getIsClosed(),
                        h.getOpensAt(),
                        h.getClosesAt(),
                        h.getEndDayOffset()))
            .toList();
    return new CustomerStoreResponse(
        summary.id(),
        summary.name(),
        summary.businessType(),
        summary.address(),
        summary.latitude(),
        summary.longitude(),
        summary.phone(),
        schedule);
  }

  public RecommendationLocationsResponse locations(String rawId, Long customerId) {
    requireCustomer(customerId);
    Long id = PurchaseRules.parseId(rawId, "candidate_id");
    var candidate =
        candidates
            .findById(id)
            .orElseThrow(() -> error("RECOMMENDATION_NOT_FOUND", "추천 후보를 찾을 수 없습니다."));
    var purchase =
        purchases
            .findById(candidate.getPurchaseRequestId())
            .orElseThrow(CustomerReadService::invalidComposition);
    if (!customerId.equals(purchase.getCustomerId()))
      throw error("FORBIDDEN", "해당 추천에 접근할 권한이 없습니다.");
    // No item/product/store lookup until ownership is established.
    var saved = items.findByRecommendationCandidateIdInOrderByIdAsc(List.of(id));
    if (saved.size() != 2
        || saved.get(0).getProductId().equals(saved.get(1).getProductId())
        || saved.stream().anyMatch(i -> !id.equals(i.getRecommendationCandidateId())))
      throw invalidComposition();
    var ids = saved.stream().map(i -> i.getProductId()).collect(Collectors.toSet());
    var byProduct =
        products.findAllById(ids).stream()
            .collect(Collectors.toMap(p -> p.getId(), Function.identity()));
    if (!byProduct.keySet().equals(ids)) throw invalidComposition();
    var storeIds = saved.stream().map(i -> byProduct.get(i.getProductId()).getStoreId()).toList();
    if (storeIds.get(0).equals(storeIds.get(1))) throw invalidComposition();
    var byStore =
        stores.findAllById(Set.copyOf(storeIds)).stream()
            .collect(Collectors.toMap(Store::getId, Function.identity()));
    if (!byStore.keySet().equals(Set.copyOf(storeIds))) throw invalidComposition();
    // Preserve recommendation item order, independent of repository IN-query result order.
    var ordered = storeIds.stream().map(byStore::get).map(s -> summary(s, true)).toList();
    var a = ordered.get(0);
    var b = ordered.get(1);
    long distance =
        StoreDistanceCalculator.straightLineMeters(
                a.latitude().doubleValue(),
                a.longitude().doubleValue(),
                b.latitude().doubleValue(),
                b.longitude().doubleValue())
            .orElseThrow(CustomerReadService::invalidLocation);
    return new RecommendationLocationsResponse(id.toString(), ordered, distance, "STRAIGHT_LINE");
  }

  private static CustomerStoreSummary summary(Store s, boolean coordinatesRequired) {
    boolean valid = valid(s.getLatitude(), 90) && valid(s.getLongitude(), 180);
    if (!valid && coordinatesRequired) throw invalidLocation();
    return new CustomerStoreSummary(
        s.getId().toString(),
        s.getName(),
        s.getBusinessType(),
        s.getAddress(),
        valid ? s.getLatitude() : null,
        valid ? s.getLongitude() : null,
        s.getPhone());
  }

  private static boolean valid(BigDecimal coordinate, int bound) {
    return coordinate != null && coordinate.abs().compareTo(BigDecimal.valueOf(bound)) <= 0;
  }

  private static void requireCustomer(Long id) {
    if (id == null || id <= 0) throw error("UNAUTHORIZED", "로그인이 필요합니다.");
  }

  private static PurchaseInputException invalidComposition() {
    return error("RECOMMENDATION_DATA_UNAVAILABLE", "두 점포 정보를 확인할 수 없습니다.");
  }

  private static PurchaseInputException invalidLocation() {
    return error("STORE_LOCATION_UNAVAILABLE", "점포 위치 정보를 확인할 수 없습니다.");
  }

  private static PurchaseInputException error(String code, String message) {
    return new PurchaseInputException(code, message, List.of());
  }
}
