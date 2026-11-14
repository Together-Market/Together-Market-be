package com.togethermarket.reservation.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "store_orders")
public class StoreOrder {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "reservation_id", nullable = false)
  private Long reservationId;

  @Column(name = "store_id", nullable = false)
  private Long storeId;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "quantity", nullable = false)
  private Integer quantity;

  @Column(name = "unit_price_snapshot", nullable = false, precision = 12, scale = 0)
  private BigDecimal unitPriceSnapshot;

  @Column(name = "product_name_snapshot", nullable = false, length = 200)
  private String productNameSnapshot;

  @Column(name = "pickup_address_snapshot", nullable = false, length = 700)
  private String pickupAddressSnapshot;

  @Column(name = "requested_pickup_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime requestedPickupAt;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "approval_status", nullable = false, length = 20)
  private ApprovalStatus approvalStatus;

  @Column(name = "responded_at", nullable = true, columnDefinition = "datetime")
  private LocalDateTime respondedAt;

  @Column(name = "rejection_reason", nullable = true, columnDefinition = "text")
  private String rejectionReason;

  protected StoreOrder() {}

  public StoreOrder(
      Long reservationId,
      Long storeId,
      Long productId,
      Integer quantity,
      BigDecimal unitPriceSnapshot,
      String productNameSnapshot,
      String pickupAddressSnapshot,
      LocalDateTime requestedPickupAt,
      ApprovalStatus approvalStatus,
      LocalDateTime respondedAt,
      String rejectionReason) {
    this.reservationId = reservationId;
    this.storeId = storeId;
    this.productId = productId;
    this.quantity = quantity;
    this.unitPriceSnapshot = unitPriceSnapshot;
    this.productNameSnapshot = productNameSnapshot;
    this.pickupAddressSnapshot = pickupAddressSnapshot;
    this.requestedPickupAt = requestedPickupAt;
    this.approvalStatus = approvalStatus;
    this.respondedAt = respondedAt;
    this.rejectionReason = rejectionReason;
  }

  public Long getId() {
    return id;
  }

  public Long getReservationId() {
    return reservationId;
  }

  public Long getStoreId() {
    return storeId;
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

  public String getProductNameSnapshot() {
    return productNameSnapshot;
  }

  public String getPickupAddressSnapshot() {
    return pickupAddressSnapshot;
  }

  public LocalDateTime getRequestedPickupAt() {
    return requestedPickupAt;
  }

  public ApprovalStatus getApprovalStatus() {
    return approvalStatus;
  }

  public LocalDateTime getRespondedAt() {
    return respondedAt;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public void expireIfPending() {
    if (approvalStatus == ApprovalStatus.PENDING) approvalStatus = ApprovalStatus.EXPIRED;
  }
}
