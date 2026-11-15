package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.RecommendationInput;
import com.togethermarket.recommendation.service.StoreDistanceCalculator;
import com.togethermarket.recommendation.service.candidate.CandidateData.Product;
import com.togethermarket.recommendation.service.candidate.CandidateData.Purchase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Builds a bounded AI input from server catalog data; does not persist candidate IDs or ranks. */
public final class CandidateGenerator {
  private static final int SHORTLIST_PER_AREA = 256;
  private static final Comparator<Pair> PAIR_ORDER =
      Comparator.comparingInt(Pair::recipientTier)
          .reversed()
          .thenComparing(Comparator.comparingDouble(Pair::score).reversed())
          .thenComparing(p -> p.a().id())
          .thenComparing(p -> p.b().id());
  private final CatalogSource catalog;
  private final ProductEligibility eligibility;
  private final Limits limits;

  /**
   * Catalog batch size is an I/O size, never a total product limit. AI receives at most 12 pairs.
   */
  public record Limits(
      int catalogBatchSize, int maxCandidates, int scoreFirstCount, double distanceScaleMeters) {
    public Limits {
      if (catalogBatchSize < 2
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
      Long categoryId,
      Set<Long> siblingCategoryIds,
      Map<Long, Double> productScores,
      PreferenceSignals text,
      RecipientPriority recipient) {
    public Preferences(
        Long categoryId, Set<Long> siblings, Map<Long, Double> scores, PreferenceSignals text) {
      this(categoryId, siblings, scores, text, RecipientPriority.none());
    }

    public Preferences(Long categoryId, Set<Long> siblings, Map<Long, Double> scores) {
      this(categoryId, siblings, scores, PreferenceSignals.none());
    }

    public Preferences {
      Objects.requireNonNull(text);
      Objects.requireNonNull(recipient);
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
    if (ids.size() < 2 || !purchase.pickupAt().toInstant().isAfter(evaluatedAt)) return List.of();
    var orderedIds = ids.stream().sorted().toList();
    var products = new ArrayList<Product>();
    for (int start = 0; start < orderedIds.size(); start += limits.catalogBatchSize()) {
      var batchIds =
          Set.copyOf(
              orderedIds.subList(
                  start, Math.min(start + limits.catalogBatchSize(), orderedIds.size())));
      var batch =
          CandidateRevalidator.loadCatalog(catalog, batchIds).values().stream()
              .filter(p -> p.priceAmount() <= purchase.budgetAmount())
              .filter(p -> RecommendationArea.of(p) != null)
              .filter(p -> !preferences.text().excludes(p.name()))
              .toList();
      var checks =
          eligibility.checkAll(
              batch.stream().map(Product::id).toList(), purchase.pickupAt(), evaluatedAt);
      batch.stream()
          .filter(p -> Objects.requireNonNull(checks.get(p.id())).allowed())
          .forEach(products::add);
    }
    products.sort(Comparator.comparing(Product::id));
    var areas = new HashMap<Long, RecommendationArea>();
    var productScores = new HashMap<Long, Double>();
    var matches = new HashMap<Long, Long>();
    var recipientKinds = new HashMap<Long, Integer>();
    var businessIds = new HashMap<String, Integer>();
    for (var product : products) {
      matches.put(product.id(), preferences.text().matches(product.name()));
      recipientKinds.put(product.id(), preferences.recipient().productKind(product.name()));
      businessIds.computeIfAbsent(product.businessType(), key -> businessIds.size());
      areas.put(product.id(), RecommendationArea.of(product));
      productScores.put(
          product.id(),
          25 * purpose(product, preferences)
              + 15 * preferences.productScores().getOrDefault(product.id(), 0.0));
    }
    // Score every valid pair, retaining a bounded shortlist per area rather than millions of pairs.
    var pools = new EnumMap<RecommendationArea, PriorityQueue<Pair>>(RecommendationArea.class);
    for (var area : RecommendationArea.values())
      pools.put(area, new PriorityQueue<>(PAIR_ORDER.reversed()));
    var businessPools =
        new EnumMap<RecommendationArea, Map<Long, PriorityQueue<Pair>>>(RecommendationArea.class);
    for (var area : RecommendationArea.values()) businessPools.put(area, new HashMap<>());
    var perProduct = new HashMap<Long, PriorityQueue<Pair>>();
    for (var p : products) perProduct.put(p.id(), new PriorityQueue<>(PAIR_ORDER.reversed()));
    for (int i = 0; i < products.size(); i++) {
      Product a = products.get(i);
      var area = areas.get(a.id());
      var pool = pools.get(area);
      for (int j = i + 1; j < products.size(); j++) {
        Product b = products.get(j);
        if (areas.get(b.id()) != area
            || a.storeId().equals(b.storeId())
            || a.storeIdentity().equals(b.storeIdentity())
            || (!preferences.recipient().parents()
                && !preferences.text().accepts(matches.get(a.id()), matches.get(b.id())))
            || b.priceAmount() > purchase.budgetAmount() - a.priceAmount()) continue;
        var distance =
            StoreDistanceCalculator.preciseStraightLineMeters(
                a.latitude(), a.longitude(), b.latitude(), b.longitude());
        double meters = distance.orElseThrow();
        if (!StorePairDistance.allows(meters)) continue;
        double score =
            100 * preferences.text().coverage(matches.get(a.id()), matches.get(b.id()))
                + productScores.get(a.id())
                + productScores.get(b.id())
                + 20 * Math.max(0, 1 - meters / limits.distanceScaleMeters());
        int businessA = businessIds.get(a.businessType());
        int businessB = businessIds.get(b.businessType());
        long businessKey =
            ((long) Math.min(businessA, businessB) << 32) | Math.max(businessA, businessB);
        var businessPool =
            businessPools
                .get(area)
                .computeIfAbsent(businessKey, key -> new PriorityQueue<>(PAIR_ORDER.reversed()));
        var pair =
            new Pair(
                a,
                b,
                meters,
                score,
                RecipientPriority.pairTier(recipientKinds.get(a.id()), recipientKinds.get(b.id())));
        if (pool.size() < SHORTLIST_PER_AREA
            || PAIR_ORDER.compare(pair, pool.peek()) <= 0
            || businessPool.size() < limits.maxCandidates()
            || PAIR_ORDER.compare(pair, businessPool.peek()) <= 0
            || perProduct.get(a.id()).size() < 4
            || PAIR_ORDER.compare(pair, perProduct.get(a.id()).peek()) <= 0
            || perProduct.get(b.id()).size() < 4
            || PAIR_ORDER.compare(pair, perProduct.get(b.id()).peek()) <= 0) {
          retain(pool, pair, SHORTLIST_PER_AREA);
          retain(businessPool, pair, limits.maxCandidates());
          retain(perProduct.get(a.id()), pair, 4);
          retain(perProduct.get(b.id()), pair, 4);
        }
      }
    }
    var retained = new HashSet<Pair>();
    pools.values().forEach(retained::addAll);
    businessPools.values().forEach(groups -> groups.values().forEach(retained::addAll));
    perProduct.values().forEach(retained::addAll);
    var pairs = new ArrayList<>(retained);
    pairs.sort(PAIR_ORDER);
    var selected = new ArrayList<Pair>();
    var productUses = new HashMap<Long, Integer>();
    var storeUses = new HashMap<String, Integer>();
    var nameUses = new HashMap<List<String>, Integer>();
    var areaUses = new HashSet<RecommendationArea>();
    var businessUses = new HashMap<List<String>, Integer>();
    while (!pairs.isEmpty() && selected.size() < limits.maxCandidates()) {
      pairs.removeIf(
          pair ->
              productUses.getOrDefault(pair.a().id(), 0) >= 3
                  || productUses.getOrDefault(pair.b().id(), 0) >= 3
                  || storeUses.getOrDefault(pair.a().storeIdentity(), 0) >= 3
                  || storeUses.getOrDefault(pair.b().storeIdentity(), 0) >= 3
                  || nameUses.getOrDefault(pair.names(), 0) >= 2);
      if (pairs.isEmpty()) break;
      // Recipient fit dominates preference coverage, distance and diversity adjustments.
      var pair =
          pairs.stream()
              .min(
                  Comparator.comparingInt(Pair::recipientTier)
                      .reversed()
                      .thenComparingDouble(
                          p ->
                              -(p.score()
                                  + (areaUses.contains(areas.get(p.a().id())) ? 0 : 10)
                                  - 20 * businessUses.getOrDefault(p.businesses(), 0)
                                  - 50 * nameUses.getOrDefault(p.names(), 0)
                                  - 20
                                      * (productUses.getOrDefault(p.a().id(), 0)
                                          + productUses.getOrDefault(p.b().id(), 0))
                                  - 10
                                      * (storeUses.getOrDefault(p.a().storeIdentity(), 0)
                                          + storeUses.getOrDefault(p.b().storeIdentity(), 0))))
                      .thenComparing(PAIR_ORDER))
              .orElseThrow();
      pairs.remove(pair);
      selected.add(pair);
      productUses.merge(pair.a().id(), 1, Integer::sum);
      productUses.merge(pair.b().id(), 1, Integer::sum);
      storeUses.merge(pair.a().storeIdentity(), 1, Integer::sum);
      storeUses.merge(pair.b().storeIdentity(), 1, Integer::sum);
      nameUses.merge(pair.names(), 1, Integer::sum);
      businessUses.merge(pair.businesses(), 1, Integer::sum);
      areaUses.add(areas.get(pair.a().id()));
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

  private static void retain(PriorityQueue<Pair> pool, Pair pair, int limit) {
    if (pool.size() < limit) pool.add(pair);
    else if (PAIR_ORDER.compare(pair, pool.peek()) < 0) {
      pool.poll();
      pool.add(pair);
    }
  }

  private record Pair(Product a, Product b, Double distance, double score, int recipientTier) {
    List<String> names() {
      return CombinationDiversity.names(List.of(a.name(), b.name()));
    }

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
          true,
          p.storeIdentity());
    }
  }
}
