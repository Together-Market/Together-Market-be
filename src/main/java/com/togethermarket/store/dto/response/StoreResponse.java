package com.togethermarket.store.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.store.entity.Store;
import java.math.BigDecimal;

public record StoreResponse(
    String id,
    String name,
    @JsonProperty("business_type") String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String phone) {
  public static StoreResponse from(Store s) {
    return new StoreResponse(
        s.getId().toString(),
        s.getName(),
        s.getBusinessType(),
        s.getAddress(),
        s.getLatitude(),
        s.getLongitude(),
        s.getPhone());
  }
}
