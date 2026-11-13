package com.togethermarket.recommendation.repository;

import com.togethermarket.recommendation.entity.RecommendationItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {}
