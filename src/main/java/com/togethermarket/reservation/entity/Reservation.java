package com.togethermarket.reservation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservations")
public class Reservation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "customer_id", nullable = false)
  private Long customerId;

  @Column(name = "recommendation_candidate_id", nullable = false)
  private Long recommendationCandidateId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", nullable = false, length = 20)
  private ReservationStatus status;

  @Column(name = "failure_code", nullable = true, length = 30)
  private String failureCode;

  @Column(name = "customer_name", nullable = false, length = 100)
  private String customerName;

  @Column(name = "customer_phone", nullable = false, length = 30)
  private String customerPhone;

  @Column(name = "customer_note", nullable = true, columnDefinition = "text")
  private String customerNote;

  @Column(name = "approval_deadline_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime approvalDeadlineAt;

  @Column(name = "request_key", nullable = false, length = 100)
  private String requestKey;

  @Column(
      name = "request_payload_hash",
      nullable = false,
      length = 64,
      columnDefinition = "char(64)")
  private String requestPayloadHash;

  @Column(name = "created_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createdAt;

  protected Reservation() {}

  public Reservation(
      Long customerId,
      Long recommendationCandidateId,
      ReservationStatus status,
      String failureCode,
      String customerName,
      String customerPhone,
      String customerNote,
      LocalDateTime approvalDeadlineAt,
      String requestKey,
      String requestPayloadHash,
      LocalDateTime createdAt) {
    this.customerId = customerId;
    this.recommendationCandidateId = recommendationCandidateId;
    this.status = status;
    this.failureCode = failureCode;
    this.customerName = customerName;
    this.customerPhone = customerPhone;
    this.customerNote = customerNote;
    this.approvalDeadlineAt = approvalDeadlineAt;
    this.requestKey = requestKey;
    this.requestPayloadHash = requestPayloadHash;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public Long getRecommendationCandidateId() {
    return recommendationCandidateId;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerPhone() {
    return customerPhone;
  }

  public String getCustomerNote() {
    return customerNote;
  }

  public LocalDateTime getApprovalDeadlineAt() {
    return approvalDeadlineAt;
  }

  public String getRequestKey() {
    return requestKey;
  }

  public String getRequestPayloadHash() {
    return requestPayloadHash;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
