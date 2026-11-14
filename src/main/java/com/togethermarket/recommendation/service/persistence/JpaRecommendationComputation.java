package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.service.HybridRecommendationService;
import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.candidate.CandidateGenerator;
import com.togethermarket.recommendation.service.job.RecommendationComputation;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class JpaRecommendationComputation implements RecommendationComputation {
  private final JpaRecommendationInputSource source;
  private final CandidateGenerator generator;
  private final HybridRecommendationService ranker;
  private final Clock clock;

  public JpaRecommendationComputation(
      JpaRecommendationInputSource source,
      CandidateGenerator generator,
      HybridRecommendationService ranker,
      Clock clock) {
    this.source = source;
    this.generator = generator;
    this.ranker = ranker;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public List<RankedCandidate> compute(Long requestId) {
    var input = source.load(requestId);
    var candidates =
        generator.generate(
            input.purchase(), input.productIds(), input.preferences(), clock.instant());
    return ranker.recommend(input.context(), candidates);
  }
}
