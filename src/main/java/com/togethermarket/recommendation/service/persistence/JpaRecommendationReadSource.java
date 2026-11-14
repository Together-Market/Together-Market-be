package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.entity.RecommendationCandidate;
import com.togethermarket.recommendation.entity.RecommendationItem;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.candidate.CandidateData.*;
import com.togethermarket.recommendation.service.candidate.RecommendationReadSource;
import com.togethermarket.reservation.entity.ReservationStatus;
import com.togethermarket.reservation.repository.ReservationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class JpaRecommendationReadSource implements RecommendationReadSource {
  private final PurchaseRequestRepository purchases;
  private final RecommendationCandidateRepository candidates;
  private final RecommendationItemRepository items;
  private final ReservationRepository reservations;

  public JpaRecommendationReadSource(
      PurchaseRequestRepository purchases,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      ReservationRepository reservations) {
    this.purchases = purchases;
    this.candidates = candidates;
    this.items = items;
    this.reservations = reservations;
  }

  @Override
  public Optional<Purchase> findPurchase(Long id) {
    return purchases
        .findById(id)
        .map(
            p ->
                new Purchase(
                    p.getId(),
                    p.getCustomerId(),
                    p.getBudgetAmount().longValueExact(),
                    KoreanDateTime.toResponse(p.getPreferredPickupAt()),
                    p.getStatus().name()));
  }

  @Override
  public List<SavedCandidate> findCandidates(Long id) {
    return snapshots(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(id));
  }

  @Override
  public Optional<SavedCandidate> findCandidate(Long id) {
    return candidates.findById(id).map(c -> snapshots(List.of(c)).getFirst());
  }

  @Override
  public Optional<Reservation> findReservation(Long id) {
    return reservations
        .findById(id)
        .map(
            r -> {
              var candidate =
                  candidates
                      .findById(r.getRecommendationCandidateId())
                      .orElseThrow(
                          () -> new IllegalStateException("Reservation candidate is missing"));
              return new Reservation(
                  r.getId(),
                  r.getCustomerId(),
                  candidate.getPurchaseRequestId(),
                  candidate.getId(),
                  r.getStatus() == ReservationStatus.FAILED);
            });
  }

  private List<SavedCandidate> snapshots(List<RecommendationCandidate> loaded) {
    if (loaded.isEmpty()) return List.of();
    Map<Long, List<RecommendationItem>> grouped =
        items
            .findByRecommendationCandidateIdInOrderByIdAsc(
                loaded.stream().map(RecommendationCandidate::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(RecommendationItem::getRecommendationCandidateId));
    return loaded.stream()
        .map(
            c ->
                new SavedCandidate(
                    c.getId(),
                    c.getPurchaseRequestId(),
                    c.getRank(),
                    c.getRecommendationReason(),
                    grouped.getOrDefault(c.getId(), List.of()).stream()
                        .map(
                            i ->
                                new SavedItem(
                                    i.getId(),
                                    i.getProductId(),
                                    i.getQuantity(),
                                    i.getUnitPriceSnapshot().longValueExact()))
                        .toList()))
        .toList();
  }
}
