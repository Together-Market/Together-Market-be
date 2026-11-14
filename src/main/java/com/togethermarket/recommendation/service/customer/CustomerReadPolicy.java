package com.togethermarket.recommendation.service.customer;

import com.togethermarket.product.entity.SaleStatus;
import java.util.Set;

/** Explicit integration choices; draft visibility/null contracts are not silent defaults. */
public record CustomerReadPolicy(
    Set<SaleStatus> visibleSaleStatuses, boolean requireDetailCoordinates) {
  public CustomerReadPolicy {
    visibleSaleStatuses = Set.copyOf(visibleSaleStatuses);
    if (visibleSaleStatuses.isEmpty())
      throw new IllegalArgumentException("Choose visible product statuses explicitly");
  }
}
