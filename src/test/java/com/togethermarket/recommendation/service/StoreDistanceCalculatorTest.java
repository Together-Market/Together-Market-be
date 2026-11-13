package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoreDistanceCalculatorTest {
  @Test
  void missingOrInvalidCoordinatesAreNotTurnedIntoZeroDistance() {
    assertThat(StoreDistanceCalculator.straightLineMeters(null, 127., 37., 127.)).isEmpty();
    assertThat(StoreDistanceCalculator.straightLineMeters(37., null, 37., 127.)).isEmpty();
    assertThat(StoreDistanceCalculator.straightLineMeters(37., 127., 91., 127.)).isEmpty();
    assertThat(StoreDistanceCalculator.straightLineMeters(37., 181., 37., 127.)).isEmpty();
    assertThat(StoreDistanceCalculator.straightLineMeters(Double.NaN, 127., 37., 127.)).isEmpty();
    assertThat(StoreDistanceCalculator.straightLineMeters(37., Double.POSITIVE_INFINITY, 37., 127.))
        .isEmpty();
  }

  @Test
  void samePointIsZeroAndOneDegreeAtEquatorIsRoundedInMeters() {
    assertThat(StoreDistanceCalculator.straightLineMeters(0., 0., 0., 0.)).hasValue(0);
    assertThat(StoreDistanceCalculator.straightLineMeters(0., 0., 0., 1.)).hasValue(111195);
    assertThat(StoreDistanceCalculator.straightLineMeters(0., 1., 0., 0.)).hasValue(111195);
  }

  @Test
  void handlesAntimeridianAndAntipodalRounding() {
    assertThat(StoreDistanceCalculator.straightLineMeters(0., 179.9, 0., -179.9)).hasValue(22239);
    assertThat(StoreDistanceCalculator.straightLineMeters(0., 0., 0., 180.)).hasValue(20015087);
  }
}
