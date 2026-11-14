package com.togethermarket.product.service;

import com.togethermarket.product.entity.Product;
import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.entity.StoreHour;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultProductAvailabilityService implements ProductAvailabilityService {
  private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
  private final ProductRepository products;
  private final StoreRepository stores;
  private final StoreHourRepository hours;
  private final StoreScheduleEvaluator evaluator;

  public DefaultProductAvailabilityService(
      ProductRepository products,
      StoreRepository stores,
      StoreHourRepository hours,
      StoreScheduleEvaluator evaluator) {
    this.products = products;
    this.stores = stores;
    this.hours = hours;
    this.evaluator = evaluator;
  }

  @Override
  public AvailabilityResult check(Long productId, Instant pickupAt, Instant checkedAt) {
    Objects.requireNonNull(productId);
    Objects.requireNonNull(pickupAt);
    Objects.requireNonNull(checkedAt);
    Product product = products.findById(productId).orElse(null);
    if (product == null)
      return new AvailabilityResult(List.of(AvailabilityReason.PRODUCT_NOT_FOUND));
    if (!stores.existsById(product.getStoreId())) {
      return new AvailabilityResult(List.of(AvailabilityReason.STORE_NOT_FOUND));
    }
    return evaluate(product, hours.findByStoreId(product.getStoreId()), pickupAt, checkedAt);
  }

  @Override
  public Map<Long, AvailabilityResult> checkAll(
      Collection<Long> productIds, Instant pickupAt, Instant checkedAt) {
    Objects.requireNonNull(pickupAt);
    Objects.requireNonNull(checkedAt);
    var result = new HashMap<Long, AvailabilityResult>();
    var ids = productIds.stream().distinct().toList();
    for (int start = 0; start < ids.size(); start += 500) {
      var batch = ids.subList(start, Math.min(start + 500, ids.size()));
      var found = products.findAllById(batch);
      var storeIds = found.stream().map(Product::getStoreId).distinct().toList();
      var existing =
          stores.findAllById(storeIds).stream().map(Store::getId).collect(Collectors.toSet());
      var schedules =
          hours.findByStoreIdIn(storeIds).stream()
              .collect(Collectors.groupingBy(StoreHour::getStoreId));
      for (Long id : batch)
        result.put(id, new AvailabilityResult(List.of(AvailabilityReason.PRODUCT_NOT_FOUND)));
      for (var product : found) {
        result.put(
            product.getId(),
            existing.contains(product.getStoreId())
                ? evaluate(
                    product,
                    schedules.getOrDefault(product.getStoreId(), List.of()),
                    pickupAt,
                    checkedAt)
                : new AvailabilityResult(List.of(AvailabilityReason.STORE_NOT_FOUND)));
      }
    }
    return Map.copyOf(result);
  }

  private AvailabilityResult evaluate(
      Product product, List<StoreHour> schedules, Instant pickupAt, Instant checkedAt) {
    List<AvailabilityReason> reasons = new ArrayList<>();
    if (product.getSaleStatus() != SaleStatus.ON_SALE)
      reasons.add(AvailabilityReason.PRODUCT_NOT_ON_SALE);
    if (!pickupAt.isAfter(checkedAt)) reasons.add(AvailabilityReason.PICKUP_NOT_IN_FUTURE);
    if (Duration.between(checkedAt, pickupAt)
            .compareTo(Duration.ofMinutes(product.getPreparationMinutes()))
        < 0) {
      reasons.add(AvailabilityReason.PREPARATION_TIME_INSUFFICIENT);
    }
    LocalDateTime localPickup = LocalDateTime.ofInstant(pickupAt, STORE_ZONE);
    if (!evaluator.allows(schedules, HourType.BUSINESS, localPickup))
      reasons.add(AvailabilityReason.OUTSIDE_BUSINESS_HOURS);
    if (!evaluator.allows(schedules, HourType.PICKUP, localPickup))
      reasons.add(AvailabilityReason.OUTSIDE_PICKUP_HOURS);
    return new AvailabilityResult(reasons);
  }
}
