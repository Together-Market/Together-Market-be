package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** PUR-03/04 payload. Shared envelope/auth/Entity mapping belongs to the integration adapter. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PurchaseRequestResponse(
    @JsonSerialize(using = ToStringSerializer.class) Long id,
    @JsonProperty("customer_id") @JsonSerialize(using = ToStringSerializer.class) Long customerId,
    @JsonProperty("purpose_category_id") @JsonSerialize(using = ToStringSerializer.class)
        Long purposeCategoryId,
    @JsonProperty("budget_amount") long budgetAmount,
    @JsonProperty("preferred_pickup_at") OffsetDateTime preferredPickupAt,
    List<Answer> answers,
    String status,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("failure_code") String failureCode) {
  public PurchaseRequestResponse {
    answers = List.copyOf(answers);
  }

  public record Answer(
      @JsonProperty("question_code") String questionCode, String value, String label) {}
}
