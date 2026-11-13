package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.candidate.CandidateData.CheckedCandidate;
import com.togethermarket.recommendation.service.candidate.CandidateData.CurrentItem;
import com.togethermarket.recommendation.service.candidate.CandidateData.Product;
import com.togethermarket.recommendation.service.candidate.CandidateData.Purchase;
import com.togethermarket.recommendation.service.candidate.CandidateData.SavedCandidate;
import com.togethermarket.recommendation.service.candidate.CandidateData.UnavailableReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CandidateRevalidator {
  private final CatalogSource catalog;
  private final ProductEligibility eligibility;

  public CandidateRevalidator(CatalogSource catalog, ProductEligibility eligibility) {
    this.catalog = Objects.requireNonNull(catalog);
    this.eligibility = Objects.requireNonNull(eligibility);
  }

  public List<CheckedCandidate> check(
      Purchase purchase, List<SavedCandidate> candidates, Instant evaluatedAt) {
    Objects.requireNonNull(evaluatedAt);
    var candidateIds = new HashSet<Long>();
    for (var candidate : candidates) {
      if (!candidate.purchaseRequestId().equals(purchase.id())
          || !candidateIds.add(candidate.id())) {
        throw new IllegalStateException("Mixed purchase requests or duplicate candidate IDs");
      }
    }
    if (candidates.isEmpty()) return List.of();
    Set<Long> productIds =
        candidates.stream()
            .flatMap(c -> c.items().stream())
            .map(item -> item.productId())
            .collect(Collectors.toSet());
    Map<Long, Product> products = loadCatalog(catalog, productIds);
    var checks = new HashMap<Long, ProductEligibility.Result>();
    var result = new ArrayList<CheckedCandidate>();
    for (var candidate : candidates) {
      var reasons = new LinkedHashSet<UnavailableReason>();
      var items = new ArrayList<CurrentItem>();
      var stores = new HashSet<Long>();
      long total = 0;
      for (var item : candidate.items()) {
        Product product = products.get(item.productId());
        items.add(new CurrentItem(item, product));
        stores.add(product.storeId());
        total = Math.addExact(total, Math.multiplyExact(product.priceAmount(), item.quantity()));
        var check =
            checks.computeIfAbsent(
                product.id(),
                id ->
                    Objects.requireNonNull(
                        eligibility.check(id, purchase.pickupAt(), evaluatedAt)));
        for (String code : check.reasonCodes())
          reasons.add(new UnavailableReason(product.id(), code));
      }
      if (stores.size() != 2) reasons.add(new UnavailableReason(null, "DISTINCT_STORES_REQUIRED"));
      if (total > purchase.budgetAmount())
        reasons.add(new UnavailableReason(null, "BUDGET_EXCEEDED"));
      if (!purchase.pickupAt().toInstant().isAfter(evaluatedAt)) {
        reasons.add(new UnavailableReason(null, "PICKUP_TIME_PASSED"));
      }
      result.add(new CheckedCandidate(candidate, items, total, List.copyOf(reasons)));
    }
    return List.copyOf(result);
  }

  static Map<Long, Product> loadCatalog(CatalogSource source, Set<Long> ids) {
    Map<Long, Product> products = Map.copyOf(source.findProducts(Set.copyOf(ids)));
    // Deletion is not a supported product lifecycle in this release: missing data is an adapter
    // failure. Never fabricate a current price from a historical snapshot.
    if (!products.keySet().equals(ids)
        || products.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().id()))) {
      throw new IllegalStateException(
          "Catalog must return exactly the requested product snapshots");
    }
    return products;
  }
}
