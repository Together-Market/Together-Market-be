package com.togethermarket.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record RejectOrderRequest(
    @JsonProperty("rejection_reason") @NotBlank @Size(max = 1000) String rejectionReason) {}
