package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.product.entity.Product;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.job.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxy transactions are essential when called from an afterCommit callback. */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JpaRecommendationJobStore implements RecommendationJobStore {
  private final PurchaseRequestRepository purchases;
  private final RecommendationCandidateRepository candidates;
  private final RecommendationItemRepository items;
  private final Clock clock;
  private final ProductRepository products;
  private final ProductAvailabilityService availability;

  public JpaRecommendationJobStore(
      PurchaseRequestRepository purchases,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      Clock clock,
      ProductRepository products,
      ProductAvailabilityService availability) {
    this.purchases = purchases;
    this.candidates = candidates;
    this.items = items;
    this.clock = clock;
    this.products = products;
    this.availability = availability;
  }

  @Override
  public Optional<Lease> claim(Long id, UUID token, Instant ignored, Duration timeout) {
    var found = purchases.findLockedById(id);
    if (found.isEmpty() || found.get().getStatus() != RecommendationStatus.RECOMMENDING)
      return Optional.empty();
    var row = found.get();
    var before = state(row);
    var after = before.claim(token, clock.instant(), timeout);
    applyTerminal(row, after);
    if (!after.owns(token) || before.claimToken() != null) return Optional.empty();
    row.claimRecommendation(token.toString(), KoreanDateTime.toDatabase(after.deadline()));
    purchases.flush();
    return Optional.of(
        new Lease(id, token, KoreanDateTime.toInstant(row.getRecommendationDeadlineAt())));
  }

  @Override
  public boolean complete(Lease lease, List<RankedCandidate> result, Instant ignored) {
    var row = owned(lease);
    if (row == null) return false;
    var after = state(row).complete(lease.token(), clock.instant());
    if (!after.status().equals("READY")) {
      applyTerminal(row, after);
      return false;
    }
    validate(row, result);
    if (candidates.existsByPurchaseRequestId(row.getId()))
      throw new IllegalStateException("Recommendation results already exist");
    // AI may take seconds: refresh database facts in this short persistence transaction.
    // This is a recommendation snapshot, not a stock hold; BE1 validates again at booking.
    var productIds = new HashSet<Long>();
    result.forEach(
        c -> c.candidate().products().forEach(p -> productIds.add(Long.valueOf(p.id()))));
    Map<Long, Product> current = new HashMap<>();
    if (!productIds.isEmpty())
      products.findAllById(productIds).forEach(p -> current.put(p.getId(), p));
    if (!current.keySet().equals(productIds))
      throw new IllegalStateException("Recommendation product disappeared before persistence");
    var eligible = new HashMap<Long, Boolean>();
    var checkedAt = clock.instant();
    var pickupAt = KoreanDateTime.toInstant(row.getPreferredPickupAt());
    int rank = 1;
    for (var selected : result) {
      var pair = selected.candidate().products();
      var first = current.get(Long.valueOf(pair.get(0).id()));
      var second = current.get(Long.valueOf(pair.get(1).id()));
      var total = first.getPriceAmount().add(second.getPriceAmount());
      if (first.getStoreId().equals(second.getStoreId())
          || !first.getStoreId().toString().equals(pair.get(0).store_id())
          || !second.getStoreId().toString().equals(pair.get(1).store_id())
          || total.compareTo(row.getBudgetAmount()) > 0
          || !eligible.computeIfAbsent(
              first.getId(), id -> availability.check(id, pickupAt, checkedAt).available())
          || !eligible.computeIfAbsent(
              second.getId(), id -> availability.check(id, pickupAt, checkedAt).available()))
        continue;
      var saved =
          candidates.save(new RecommendationCandidate(row.getId(), rank++, selected.reason()));
      for (var p : selected.candidate().products()) {
        items.save(
            new RecommendationItem(
                saved.getId(),
                Long.valueOf(p.id()),
                1,
                current.get(Long.valueOf(p.id())).getPriceAmount()));
      }
    }
    items.flush();
    // A slow insert must roll back all inserted results rather than commit them after the deadline.
    if (!clock.instant().isBefore(KoreanDateTime.toInstant(row.getRecommendationDeadlineAt()))) {
      throw new IllegalStateException("Recommendation deadline elapsed during persistence");
    }
    row.finishRecommendation(true, null);
    purchases.flush();
    return true;
  }

  @Override
  public boolean fail(Lease lease, RecommendationFailureCode code, Instant ignored) {
    var row = owned(lease);
    if (row == null) return false;
    applyTerminal(row, state(row).fail(lease.token(), code, clock.instant()));
    return true;
  }

  @Override
  public int expire(Instant ignored, Duration timeout, int limit) {
    if (limit < 1 || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Invalid expiry batch");
    Instant now = clock.instant();
    var ids =
        purchases.findExpiredIds(
            RecommendationStatus.RECOMMENDING,
            KoreanDateTime.toDatabase(now),
            KoreanDateTime.toDatabase(now.minus(timeout)),
            PageRequest.of(0, limit));
    int count = 0;
    for (Long id : ids) {
      var found = purchases.findLockedById(id);
      if (found.isEmpty() || found.get().getStatus() != RecommendationStatus.RECOMMENDING) continue;
      var row = found.get();
      var expired = state(row).expire(clock.instant(), timeout);
      if (expired.status().equals("FAILED")) {
        applyTerminal(row, expired);
        count++;
      }
    }
    return count;
  }

  private PurchaseRequest owned(Lease lease) {
    var found = purchases.findLockedById(lease.requestId());
    return found
        .filter(
            p ->
                p.getStatus() == RecommendationStatus.RECOMMENDING
                    && lease.token().toString().equals(p.getRecommendationClaimToken()))
        .orElse(null);
  }

  private static RecommendationJobState state(PurchaseRequest p) {
    return new RecommendationJobState(
        p.getId(),
        KoreanDateTime.toInstant(p.getCreatedAt()),
        p.getStatus().name(),
        p.getRecommendationClaimToken() == null
            ? null
            : UUID.fromString(p.getRecommendationClaimToken()),
        p.getRecommendationDeadlineAt() == null
            ? null
            : KoreanDateTime.toInstant(p.getRecommendationDeadlineAt()),
        null);
  }

  private static void applyTerminal(PurchaseRequest row, RecommendationJobState after) {
    if (after.status().equals("FAILED"))
      row.finishRecommendation(false, after.failureCode().name());
  }

  private static void validate(PurchaseRequest row, List<RankedCandidate> result) {
    if (result.size() > 6) throw new IllegalArgumentException("Too many recommendation results");
    var pairs = new HashSet<List<Long>>();
    var keys = new HashSet<String>();
    for (var selected : result) {
      var c = selected.candidate();
      var ids = c.products().stream().map(p -> Long.valueOf(p.id())).sorted().toList();
      if (ids.stream().anyMatch(id -> id <= 0)
          || !pairs.add(ids)
          || !keys.add(c.candidate_id())
          || selected.reason() == null
          || selected.reason().isBlank()
          || selected.reason().codePointCount(0, selected.reason().length()) > 120
          || !Double.isFinite(selected.score())
          || selected.score() < 0
          || selected.score() > 100
          || !c.pickup_eligible()
          || c.products().stream().anyMatch(p -> !p.is_available())
          || c.totalAmount() > row.getBudgetAmount().longValueExact())
        throw new IllegalArgumentException("Invalid recommendation result");
    }
  }
}
