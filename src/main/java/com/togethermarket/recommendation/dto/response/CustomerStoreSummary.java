package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Customer-visible store fields only; no owner account or internal public-data matching fields. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CustomerStoreSummary(
    String id,
    String name,
    @JsonProperty("business_type") String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String phone) {}
