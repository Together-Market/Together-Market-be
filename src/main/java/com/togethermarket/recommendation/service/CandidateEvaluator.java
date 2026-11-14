package com.togethermarket.recommendation.service;

import com.togethermarket.recommendation.service.RecommendationInput.Candidate;
import com.togethermarket.recommendation.service.RecommendationInput.Context;
import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import java.util.List;

@FunctionalInterface
public interface CandidateEvaluator {
  List<Evaluation> evaluate(Context context, List<Candidate> candidates);
}
