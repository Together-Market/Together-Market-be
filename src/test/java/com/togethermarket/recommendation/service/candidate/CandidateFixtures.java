package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class CandidateFixtures implements RecommendationReadSource, CatalogSource {
  static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");
  static final OffsetDateTime PICKUP = OffsetDateTime.parse("2026-11-14T15:00:00+09:00");
  Purchase purchase = new Purchase(1L, 7L, 10000, PICKUP, "READY");
  Reservation reservation = new Reservation(5L, 7L, 1L, 1L, true);
  List<SavedCandidate> candidates = List.of(saved(1, 1, 1, 2), saved(2, 2, 1, 3));
  Map<Long, Product> products =
      new HashMap<>(
          Map.of(1L, product(1, 1, 3000), 2L, product(2, 2, 4000), 3L, product(3, 2, 5000)));
  int catalogReads;
  int sourceReads;

  static Product product(long id, long storeId, long price) {
    return new Product(id, storeId, "상품 " + id, null, "디저트", price, Set.of(11L), 37.2, 127.1);
  }

  static SavedCandidate saved(long id, int rank, long a, long b) {
    return new SavedCandidate(
        id,
        1L,
        rank,
        "추천 이유",
        List.of(new SavedItem(id * 2, a, 1, 3000), new SavedItem(id * 2 + 1, b, 1, 4000)));
  }

  public Map<Long, Product> findProducts(Set<Long> ids) {
    catalogReads++;
    return products.entrySet().stream()
        .filter(e -> ids.contains(e.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public Optional<Purchase> findPurchase(Long id) {
    sourceReads++;
    return Optional.ofNullable(purchase).filter(p -> p.id().equals(id));
  }

  public List<SavedCandidate> findCandidates(Long id) {
    sourceReads++;
    return candidates;
  }

  public Optional<SavedCandidate> findCandidate(Long id) {
    sourceReads++;
    return candidates.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  public Optional<Reservation> findReservation(Long id) {
    sourceReads++;
    return Optional.ofNullable(reservation).filter(r -> r.id().equals(id));
  }
}
