package com.togethermarket.product.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public interface ProductAvailabilityService {
  default Map<Long, AvailabilityResult> checkAll(
      Collection<Long> productIds, Instant pickupAt, Instant checkedAt) {
    var results = new HashMap<Long, AvailabilityResult>();
    for (Long id : productIds) results.computeIfAbsent(id, key -> check(key, pickupAt, checkedAt));
    return Map.copyOf(results);
  }

  AvailabilityResult check(Long productId, Instant pickupAt, Instant checkedAt);
}
