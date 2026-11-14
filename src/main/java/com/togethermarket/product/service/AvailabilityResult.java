package com.togethermarket.product.service;

import java.util.List;

public record AvailabilityResult(List<AvailabilityReason> reasons) {
  public AvailabilityResult {
    reasons = List.copyOf(reasons);
  }

  public boolean available() {
    return reasons.isEmpty();
  }
}
