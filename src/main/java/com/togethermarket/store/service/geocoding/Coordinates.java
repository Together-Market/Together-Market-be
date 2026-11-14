package com.togethermarket.store.service.geocoding;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A complete WGS84 pair; never combine a new value with one half of an old pair. */
public record Coordinates(BigDecimal latitude, BigDecimal longitude) {
  public Coordinates {
    if (!usable(latitude, longitude)) throw new IllegalArgumentException("Invalid coordinates");
    latitude = latitude.setScale(7, RoundingMode.HALF_UP);
    longitude = longitude.setScale(7, RoundingMode.HALF_UP);
    if (latitude.signum() == 0 && longitude.signum() == 0)
      throw new IllegalArgumentException("Invalid coordinates");
  }

  public static boolean usable(BigDecimal latitude, BigDecimal longitude) {
    return latitude != null
        && longitude != null
        && latitude.abs().compareTo(BigDecimal.valueOf(90)) <= 0
        && longitude.abs().compareTo(BigDecimal.valueOf(180)) <= 0
        && (latitude.signum() != 0 || longitude.signum() != 0);
  }
}
