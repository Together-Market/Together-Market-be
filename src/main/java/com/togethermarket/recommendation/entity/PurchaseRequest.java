package com.togethermarket.recommendation.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "purchase_requests")
public class PurchaseRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "customer_id", nullable = false)
  private Long customerId;

  @Column(name = "purpose_category_id", nullable = false)
  private Long purposeCategoryId;

  @Column(name = "budget_amount", nullable = false, precision = 12, scale = 0)
  private BigDecimal budgetAmount;

  @Column(name = "preferred_pickup_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime preferredPickupAt;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", nullable = false, length = 20)
  private RecommendationStatus status;

  @Column(name = "failure_code", nullable = true, length = 50)
  private String failureCode;

  @Column(name = "created_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createdAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "answers", nullable = false, columnDefinition = "json")
  private String answers;

  protected PurchaseRequest() {}

  public PurchaseRequest(
      Long customerId,
      Long purposeCategoryId,
      BigDecimal budgetAmount,
      LocalDateTime preferredPickupAt,
      RecommendationStatus status,
      String failureCode,
      LocalDateTime createdAt,
      String answers) {
    this.customerId = customerId;
    this.purposeCategoryId = purposeCategoryId;
    this.budgetAmount = budgetAmount;
    this.preferredPickupAt = preferredPickupAt;
    this.status = status;
    this.failureCode = failureCode;
    this.createdAt = createdAt;
    this.answers = answers;
  }

  public Long getId() {
    return id;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public Long getPurposeCategoryId() {
    return purposeCategoryId;
  }

  public BigDecimal getBudgetAmount() {
    return budgetAmount;
  }

  public LocalDateTime getPreferredPickupAt() {
    return preferredPickupAt;
  }

  public RecommendationStatus getStatus() {
    return status;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public String getAnswers() {
    return answers;
  }
}
