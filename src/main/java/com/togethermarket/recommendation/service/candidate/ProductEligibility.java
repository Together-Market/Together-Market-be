package com.togethermarket.recommendation.service.candidate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BE3 adapter boundary for BE2's participation/sale/calendar/preparation checks. */
@FunctionalInterface
public interface ProductEligibility {
  Result check(Long productId, OffsetDateTime pickupAt, Instant evaluatedAt);

  default Map<Long, Result> checkAll(
      Collection<Long> ids, OffsetDateTime pickupAt, Instant evaluatedAt) {
    var result = new HashMap<Long, Result>();
    for (Long id : ids) result.computeIfAbsent(id, key -> check(key, pickupAt, evaluatedAt));
    return Map.copyOf(result);
  }

  record Result(boolean allowed, List<String> reasonCodes) {
    public Result {
      reasonCodes = List.copyOf(reasonCodes);
      if (allowed != reasonCodes.isEmpty() || reasonCodes.stream().anyMatch(String::isBlank)) {
        throw new IllegalArgumentException(
            "Allowed requires no reasons; unavailable requires reasons");
      }
    }

    public static Result available() {
      return new Result(true, List.of());
    }

    public static Result unavailable(String code) {
      return new Result(false, List.of(code));
    }
  }
}
