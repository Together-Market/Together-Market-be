package com.togethermarket.recommendation.service.job;

import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import java.util.List;

/**
 * Load committed conditions, build server candidates, then evaluate/rank WITHOUT a long
 * transaction.
 */
@FunctionalInterface
public interface RecommendationComputation {
  List<RankedCandidate> compute(Long requestId) throws Exception;

  /**
   * Typed adapter failure. Do not infer codes from exception messages or provider response bodies.
   */
  final class Failure extends RuntimeException {
    private final RecommendationFailureCode code;

    public Failure(RecommendationFailureCode code) {
      super(java.util.Objects.requireNonNull(code).name());
      this.code = code;
    }

    public RecommendationFailureCode code() {
      return code;
    }
  }
}
