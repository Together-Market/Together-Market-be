package com.togethermarket.recommendation.service.candidate;

import java.util.Map;
import java.util.Set;

/** Implement using the shared product/store repositories; return a consistent current snapshot. */
@FunctionalInterface
public interface CatalogSource {
  Map<Long, CandidateData.Product> findProducts(Set<Long> productIds);
}
