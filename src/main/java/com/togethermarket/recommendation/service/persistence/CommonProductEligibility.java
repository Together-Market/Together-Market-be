package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.service.candidate.ProductEligibility;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Uses the BE2 calendar/sale/preparation rules without copying them. */
public final class CommonProductEligibility implements ProductEligibility {
  private final ProductAvailabilityService service;

  public CommonProductEligibility(ProductAvailabilityService service) {
    this.service = service;
  }

  @Override
  public Map<Long, Result> checkAll(
      Collection<Long> ids, OffsetDateTime pickupAt, Instant evaluatedAt) {
    var results = new HashMap<Long, Result>();
    service
        .checkAll(ids, pickupAt.toInstant(), evaluatedAt)
        .forEach(
            (id, result) ->
                results.put(
                    id,
                    new Result(
                        result.available(), result.reasons().stream().map(Enum::name).toList())));
    return Map.copyOf(results);
  }

  @Override
  public Result check(Long productId, OffsetDateTime pickupAt, Instant evaluatedAt) {
    var result = service.check(productId, pickupAt.toInstant(), evaluatedAt);
    return new Result(result.available(), result.reasons().stream().map(Enum::name).toList());
  }
}
