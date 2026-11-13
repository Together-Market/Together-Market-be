package com.togethermarket.recommendation.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "recommendation_items")
public class RecommendationItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "recommendation_candidate_id", nullable = false)
  private Long recommendationCandidateId;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "quantity", nullable = false)
  private Integer quantity;

  @Column(name = "unit_price_snapshot", nullable = false, precision = 12, scale = 0)
  private BigDecimal unitPriceSnapshot;

  protected RecommendationItem() {}

  public RecommendationItem(
      Long recommendationCandidateId,
      Long productId,
      Integer quantity,
      BigDecimal unitPriceSnapshot) {
    this.recommendationCandidateId = recommendationCandidateId;
    this.productId = productId;
    this.quantity = quantity;
    this.unitPriceSnapshot = unitPriceSnapshot;
  }

  public Long getId() {
    return id;
  }

  public Long getRecommendationCandidateId() {
    return recommendationCandidateId;
  }

  public Long getProductId() {
    return productId;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPriceSnapshot() {
    return unitPriceSnapshot;
  }
}
