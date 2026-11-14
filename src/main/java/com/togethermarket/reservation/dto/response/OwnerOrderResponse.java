package com.togethermarket.reservation.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OwnerOrderResponse(
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
    String rejectionReason,
    String reservationStatus,
    String failureCode,
    OffsetDateTime approvalDeadlineAt,
    String customerName,
    String customerPhone,
    String customerNote,
    boolean canRespond) {
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Page(List<OwnerOrderResponse> items, int page, int size, long totalCount) {}
}
