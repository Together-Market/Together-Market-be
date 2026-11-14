package com.togethermarket.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record CreateReservationRequest(
    @JsonProperty("recommendation_candidate_id") @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
        String recommendationCandidateId,
    @JsonProperty("customer_name") @NotBlank @Size(max = 100) String customerName,
    @JsonProperty("customer_phone") @NotBlank @Size(max = 30) String customerPhone,
    @JsonProperty("customer_note") @Size(max = 1000) String customerNote,
    @NotNull @Size(min = 2, max = 2) List<@NotNull @Valid Pickup> pickups,
    @JsonProperty("expected_total_amount")
        @NotNull
        @DecimalMin("0")
        @Digits(integer = 12, fraction = 0)
        BigDecimal expectedTotalAmount) {
  public record Pickup(
      @JsonProperty("store_id") @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String storeId,
      @JsonProperty("requested_pickup_at") @NotNull OffsetDateTime requestedPickupAt) {}
}
