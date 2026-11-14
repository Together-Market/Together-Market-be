package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.StoreDistanceCalculator;

/** Straight-line proximity only; does not establish a walking route or walking duration. */
public final class StorePairDistance {
  public static final double MAX_METERS = 500;

  private StorePairDistance() {}

  public static boolean allows(double meters) {
    return Double.isFinite(meters) && meters >= 0 && meters <= MAX_METERS;
  }

  public static boolean allows(Double latA, Double lonA, Double latB, Double lonB) {
    var meters = StoreDistanceCalculator.preciseStraightLineMeters(latA, lonA, latB, lonB);
    return meters.isPresent() && allows(meters.getAsDouble());
  }
}
