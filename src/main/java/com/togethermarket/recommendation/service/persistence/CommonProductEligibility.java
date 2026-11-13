package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.service.candidate.ProductEligibility;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Uses the BE2 calendar/sale/preparation rules without copying them. */
public final class CommonProductEligibility implements ProductEligibility {
  private final ProductAvailabilityService service;

  public CommonProductEligibility(ProductAvailabilityService service) {
    this.service = service;
  }

  @Override
  public Result check(Long productId, OffsetDateTime pickupAt, Instant evaluatedAt) {
    var result = service.check(productId, pickupAt.toInstant(), evaluatedAt);
    return new Result(result.available(), result.reasons().stream().map(Enum::name).toList());
  }
}
