package com.togethermarket.product.service;

import java.time.Instant;

public interface ProductAvailabilityService {
  AvailabilityResult check(Long productId, Instant pickupAt, Instant checkedAt);
}
