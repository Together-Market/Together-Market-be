package com.togethermarket.recommendation.repository;

import com.togethermarket.recommendation.entity.RecommendationCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationCandidateRepository
    extends JpaRepository<RecommendationCandidate, Long> {}
