package com.togethermarket.reservation.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReservationResponse(
    String id,
    String customerId,
    String recommendationCandidateId,
    String status,
    String failureCode,
    String customerName,
    String customerPhone,
    String customerNote,
    OffsetDateTime approvalDeadlineAt,
    OffsetDateTime createdAt,
    BigDecimal totalAmount,
    List<Order> orders) {
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Order(
      String id,
      String reservationId,
      String storeId,
      String storeName,
      String productId,
      int quantity,
      BigDecimal unitPriceSnapshot,
      String productNameSnapshot,
      String pickupAddressSnapshot,
      OffsetDateTime requestedPickupAt,
      String approvalStatus,
      OffsetDateTime respondedAt,
      String rejectionReason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Page(List<ReservationResponse> items, int page, int size, long totalCount) {}
}
