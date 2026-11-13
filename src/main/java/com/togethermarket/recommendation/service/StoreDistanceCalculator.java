package com.togethermarket.recommendation.service;

import java.util.OptionalLong;

/**
 * REC-03 straight-line distance only. HTTP policy for unavailable coordinates belongs to the
 * adapter.
 */
public final class StoreDistanceCalculator {
  private static final double EARTH_RADIUS_METERS = 6_371_000;

  private StoreDistanceCalculator() {}

  public static OptionalLong straightLineMeters(
      Double latitudeA, Double longitudeA, Double latitudeB, Double longitudeB) {
    if (!coordinate(latitudeA, 90)
        || !coordinate(latitudeB, 90)
        || !coordinate(longitudeA, 180)
        || !coordinate(longitudeB, 180)) {
      return OptionalLong.empty();
    }
    double latA = Math.toRadians(latitudeA);
    double latB = Math.toRadians(latitudeB);
    double dLat = latB - latA;
    double dLon = Math.toRadians(longitudeB - longitudeA);
    double h =
        Math.pow(Math.sin(dLat / 2), 2)
            + Math.cos(latA) * Math.cos(latB) * Math.pow(Math.sin(dLon / 2), 2);
    double centralAngle = 2 * Math.asin(Math.sqrt(Math.clamp(h, 0, 1)));
    return OptionalLong.of(Math.round(EARTH_RADIUS_METERS * centralAngle));
  }

  private static boolean coordinate(Double value, double bound) {
    return value != null && Double.isFinite(value) && Math.abs(value) <= bound;
  }
}
