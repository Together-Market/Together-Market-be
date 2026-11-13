package com.togethermarket.recommendation.service.job;

/** BE3 failure_code values, not a replacement for the shared purchase status enum. */
public enum RecommendationFailureCode {
  RECOMMENDATION_TIMEOUT,
  DISPATCH_REJECTED,
  AI_TIMEOUT,
  AI_RESPONSE_INVALID,
  AI_UNAVAILABLE,
  RECOMMENDATION_FAILED,
  RESULT_PERSISTENCE_FAILED,
  WORKER_INTERRUPTED
}
