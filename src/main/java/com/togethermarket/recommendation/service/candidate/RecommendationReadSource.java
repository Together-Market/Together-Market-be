package com.togethermarket.recommendation.service.candidate;

import java.util.List;
import java.util.Optional;

/** Read-only view of shared purchase/recommendation/reservation repositories. No AI or writes. */
public interface RecommendationReadSource {
  Optional<CandidateData.Purchase> findPurchase(Long purchaseRequestId);

  List<CandidateData.SavedCandidate> findCandidates(Long purchaseRequestId);

  Optional<CandidateData.SavedCandidate> findCandidate(Long candidateId);

  Optional<CandidateData.Reservation> findReservation(Long reservationId);
}
