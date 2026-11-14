package com.togethermarket.recommendation.service.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable transition rules. The persistence adapter must apply them under a lock/CAS. */
public record RecommendationJobState(
    Long requestId,
    Instant createdAt,
    String status,
    UUID claimToken,
    Instant deadline,
    RecommendationFailureCode failureCode) {
  public RecommendationJobState {
    if (requestId == null || requestId <= 0)
      throw new IllegalArgumentException("Positive request ID required");
    Objects.requireNonNull(createdAt);
    if (status == null
        || !Set.of("RECOMMENDING", "READY", "FAILED").contains(status)
        || (status.equals("FAILED") != (failureCode != null))
        || ((claimToken == null) != (deadline == null))
        || (deadline != null && !deadline.isAfter(createdAt))
        || (status.equals("READY") && claimToken == null)) {
      throw new IllegalArgumentException("Invalid recommendation job state");
    }
  }

  public static RecommendationJobState pending(Long requestId, Instant createdAt) {
    return new RecommendationJobState(requestId, createdAt, "RECOMMENDING", null, null, null);
  }

  public RecommendationJobState claim(UUID token, Instant now, Duration timeout) {
    Objects.requireNonNull(token);
    var checked = expire(now, timeout);
    if (!checked.status.equals("RECOMMENDING") || checked.claimToken != null) return checked;
    if (now.isBefore(createdAt)) throw new IllegalArgumentException("Claim time precedes creation");
    return new RecommendationJobState(
        requestId, createdAt, status, token, createdAt.plus(timeout), null);
  }

  public RecommendationJobState complete(UUID token, Instant now) {
    if (!owns(token)) return this;
    if (!now.isBefore(deadline)) return failed(RecommendationFailureCode.RECOMMENDATION_TIMEOUT);
    return new RecommendationJobState(requestId, createdAt, "READY", claimToken, deadline, null);
  }

  public RecommendationJobState fail(UUID token, RecommendationFailureCode code, Instant now) {
    Objects.requireNonNull(code);
    if (!owns(token)) return this;
    return failed(
        !now.isBefore(deadline) ? RecommendationFailureCode.RECOMMENDATION_TIMEOUT : code);
  }

  /** Includes committed requests never dispatched; a claim never extends the original deadline. */
  public RecommendationJobState expire(Instant now, Duration timeout) {
    Objects.requireNonNull(now);
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Positive timeout required");
    Instant due = deadline == null ? createdAt.plus(timeout) : deadline;
    return status.equals("RECOMMENDING") && !now.isBefore(due)
        ? failed(RecommendationFailureCode.RECOMMENDATION_TIMEOUT)
        : this;
  }

  public boolean owns(UUID token) {
    return status.equals("RECOMMENDING") && claimToken != null && claimToken.equals(token);
  }

  private RecommendationJobState failed(RecommendationFailureCode code) {
    return new RecommendationJobState(requestId, createdAt, "FAILED", claimToken, deadline, code);
  }
}
