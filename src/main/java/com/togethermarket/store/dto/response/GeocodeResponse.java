package com.togethermarket.store.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.store.service.geocoding.Coordinates;
import java.math.BigDecimal;

public record GeocodeResponse(
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    @JsonProperty("coordinate_source") String coordinateSource,
    @JsonProperty("storage_allowed") boolean storageAllowed) {
  public static GeocodeResponse preview(String address, Coordinates coordinates) {
    return new GeocodeResponse(
        address, coordinates.latitude(), coordinates.longitude(), "KAKAO", false);
  }
}
