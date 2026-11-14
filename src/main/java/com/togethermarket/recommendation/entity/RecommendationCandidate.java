package com.togethermarket.recommendation.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "recommendation_candidates")
public class RecommendationCandidate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "purchase_request_id", nullable = false)
  private Long purchaseRequestId;

  @Column(name = "`rank`", nullable = false)
  private Integer rank;

  @Column(name = "recommendation_reason", nullable = false, columnDefinition = "text")
  private String recommendationReason;

  protected RecommendationCandidate() {}

  public RecommendationCandidate(
      Long purchaseRequestId, Integer rank, String recommendationReason) {
    this.purchaseRequestId = purchaseRequestId;
    this.rank = rank;
    this.recommendationReason = recommendationReason;
  }

  public Long getId() {
    return id;
  }

  public Long getPurchaseRequestId() {
    return purchaseRequestId;
  }

  public Integer getRank() {
    return rank;
  }

  public String getRecommendationReason() {
    return recommendationReason;
  }
}
