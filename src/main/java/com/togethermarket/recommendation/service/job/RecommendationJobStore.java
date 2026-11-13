package com.togethermarket.recommendation.service.job;

import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable adapter contract, not a shared Repository. Each method is a SHORT atomic DB transaction.
 * Persist claimToken/deadline; a JVM map is insufficient across restarts or multiple instances.
 * Apply RecommendationJobState rules with row locking or compare-and-set, never read-then-save.
 */
public interface RecommendationJobStore {
  record Lease(Long requestId, UUID token, Instant deadline) {
    public Lease {
      if (requestId == null || requestId <= 0)
        throw new IllegalArgumentException("Positive request ID required");
      Objects.requireNonNull(token);
      Objects.requireNonNull(deadline);
    }
  }

  /**
   * Only a committed, unclaimed RECOMMENDING request can be claimed. Deadline is createdAt+timeout.
   * If already expired, atomically mark FAILED and return empty. Missing/claimed/terminal: empty.
   */
  Optional<Lease> claim(Long requestId, UUID token, Instant now, Duration timeout);

  /**
   * Atomically persist all ranked candidates/items and READY (empty list is valid), only while the
   * persisted token matches and now is strictly before the persisted deadline. Late result: persist
   * FAILED/RECOMMENDATION_TIMEOUT instead, with NO candidates. Return true only on READY
   * transition. Insert errors must roll back candidates AND state. Check time again inside the DB
   * transaction.
   */
  boolean complete(Lease lease, List<RankedCandidate> result, Instant now);

  /** Matching token + RECOMMENDING only. Deadline wins over code; terminal results never change. */
  boolean fail(Lease lease, RecommendationFailureCode code, Instant now);

  /**
   * Expire at most limit rows, oldest due first, including unclaimed committed requests. Must cover
   * other processes' claims too. Deadline takes precedence; use createdAt+timeout for unclaimed
   * rows. Return affected count. Terminal states stay unchanged. No computation/re-dispatch here.
   */
  int expire(Instant now, Duration timeout, int limit);
}
