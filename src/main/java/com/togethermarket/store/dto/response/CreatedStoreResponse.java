package com.togethermarket.store.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.store.entity.Store;
import java.math.BigDecimal;

public record CreatedStoreResponse(
    String id,
    String name,
    @JsonProperty("business_type") String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String phone,
    @JsonProperty("owner_id") String ownerId) {
  public static CreatedStoreResponse from(Store s) {
    return new CreatedStoreResponse(
        s.getId().toString(),
        s.getName(),
        s.getBusinessType(),
        s.getAddress(),
        s.getLatitude(),
        s.getLongitude(),
        s.getPhone(),
        s.getOwnerId().toString());
  }
}
