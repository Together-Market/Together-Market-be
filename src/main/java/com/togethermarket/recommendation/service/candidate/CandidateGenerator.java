package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.RecommendationInput;
import com.togethermarket.recommendation.service.StoreDistanceCalculator;
import com.togethermarket.recommendation.service.candidate.CandidateData.Product;
import com.togethermarket.recommendation.service.candidate.CandidateData.Purchase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a bounded AI input from server catalog data; does not persist candidate IDs or ranks. */
public final class CandidateGenerator {
  private final CatalogSource catalog;
  private final ProductEligibility eligibility;
  private final Limits limits;

  /**
   * Explicit configuration until candidate limits are agreed. Hybrid evaluator accepts at most 12.
   */
  public record Limits(
      int maxProducts, int maxCandidates, int scoreFirstCount, double distanceScaleMeters) {
    public Limits {
      if (maxProducts < 2
          || maxCandidates < 1
          || maxCandidates > 12
          || scoreFirstCount < 0
          || scoreFirstCount > maxCandidates
          || !Double.isFinite(distanceScaleMeters)
          || distanceScaleMeters <= 0) {
        throw new IllegalArgumentException("Invalid candidate generation limits");
      }
    }
  }

  /**
   * Scores and category tags must be derived by a trusted server adapter, never client assertions.
   */
  public record Preferences(
      Long categoryId, Set<Long> siblingCategoryIds, Map<Long, Double> productScores) {
    public Preferences {
      CandidateData.positive(categoryId);
      siblingCategoryIds = Set.copyOf(siblingCategoryIds);
      siblingCategoryIds.forEach(CandidateData::positive);
      productScores = Map.copyOf(productScores);
      productScores.forEach(
          (id, score) -> {
            CandidateData.positive(id);
            if (!Double.isFinite(score) || score < 0 || score > 1) {
              throw new IllegalArgumentException("Preference scores must be between 0 and 1");
            }
          });
    }
  }

  public CandidateGenerator(CatalogSource catalog, ProductEligibility eligibility, Limits limits) {
    this.catalog = Objects.requireNonNull(catalog);
    this.eligibility = Objects.requireNonNull(eligibility);
    this.limits = Objects.requireNonNull(limits);
  }

  public List<RecommendationInput.Candidate> generate(
      Purchase purchase,
      Collection<Long> productIds,
      Preferences preferences,
      Instant evaluatedAt) {
    Objects.requireNonNull(purchase);
    Objects.requireNonNull(preferences);
    Objects.requireNonNull(evaluatedAt);
    Set<Long> ids = Set.copyOf(productIds);
    ids.forEach(CandidateData::positive);
    if (ids.size() > limits.maxProducts()) {
      throw new IllegalArgumentException("Catalog selection exceeds configured bound");
    }
    if (ids.size() < 2 || !purchase.pickupAt().toInstant().isAfter(evaluatedAt)) return List.of();
    List<Product> products =
        CandidateRevalidator.loadCatalog(catalog, ids).values().stream()
            .sorted(Comparator.comparing(Product::id))
            .filter(p -> p.priceAmount() < purchase.budgetAmount())
            .filter(p -> eligibility.check(p.id(), purchase.pickupAt(), evaluatedAt).allowed())
            .toList();
    var pairs = new ArrayList<Pair>();
    for (int i = 0; i < products.size(); i++) {
      for (int j = i + 1; j < products.size(); j++) {
        Product a = products.get(i);
        Product b = products.get(j);
        // Subtraction prevents overflow even if the catalog adapter supplies an enormous price.
        if (a.storeId().equals(b.storeId())
            || b.priceAmount() > purchase.budgetAmount() - a.priceAmount()) continue;
        var distance =
            StoreDistanceCalculator.straightLineMeters(
                a.latitude(), a.longitude(), b.latitude(), b.longitude());
        Double meters = distance.isPresent() ? (double) distance.getAsLong() : null;
        double proximity =
            meters == null ? 0 : Math.max(0, 1 - meters / limits.distanceScaleMeters());
        double score =
            50 * (purpose(a, preferences) + purpose(b, preferences)) / 2
                + 30
                    * (preferences.productScores().getOrDefault(a.id(), 0.0)
                        + preferences.productScores().getOrDefault(b.id(), 0.0))
                    / 2
                + 20 * proximity;
        pairs.add(new Pair(a, b, meters, score));
      }
    }
    pairs.sort(
        Comparator.comparingDouble(Pair::score)
            .reversed()
            .thenComparing(p -> p.a().id())
            .thenComparing(p -> p.b().id()));
    var selected = new ArrayList<Pair>();
    var storePairs = new HashSet<List<Long>>();
    var businessPairs = new HashSet<List<String>>();
    while (!pairs.isEmpty() && selected.size() < limits.maxCandidates()) {
      int index = 0;
      if (selected.size() >= limits.scoreFirstCount()) {
        int bestNovelty = -1;
        for (int i = 0; i < pairs.size(); i++) {
          Pair pair = pairs.get(i);
          int novelty =
              (storePairs.contains(pair.stores()) ? 0 : 1)
                  + (businessPairs.contains(pair.businesses()) ? 0 : 1);
          // Equal novelty preserves pre-score and numeric ID ordering.
          if (novelty > bestNovelty) {
            index = i;
            bestNovelty = novelty;
          }
        }
      }
      Pair pair = pairs.remove(index);
      selected.add(pair);
      storePairs.add(pair.stores());
      businessPairs.add(pair.businesses());
    }
    return selected.stream().map(Pair::input).toList();
  }

  private static double purpose(Product product, Preferences preferences) {
    if (product.purposeCategoryIds().contains(preferences.categoryId())) return 1;
    return product.purposeCategoryIds().stream()
            .anyMatch(preferences.siblingCategoryIds()::contains)
        ? 0.5
        : 0;
  }

  private record Pair(Product a, Product b, Double distance, double score) {
    List<Long> stores() {
      return List.of(a.storeId(), b.storeId()).stream().sorted().toList();
    }

    List<String> businesses() {
      return List.of(a.businessType(), b.businessType()).stream().sorted().toList();
    }

    RecommendationInput.Candidate input() {
      // Ephemeral evaluator correlation key, not a database/public candidate ID.
      return new RecommendationInput.Candidate(
          "p_" + a.id() + "_" + b.id(), List.of(input(a), input(b)), distance, true);
    }

    private static RecommendationInput.Product input(Product p) {
      return new RecommendationInput.Product(
          p.id().toString(),
          p.storeId().toString(),
          p.name(),
          p.description() == null ? "" : p.description(),
          p.businessType(),
          p.priceAmount(),
          true);
    }
  }
}
