package com.togethermarket.recommendation.service.candidate;

/** Approved straight-line 500m coverage. Coordinates and provenance: docs/yongin-demo-areas.md. */
public enum RecommendationArea {
  BOJEONG(new double[][] {{37.3211736204431, 127.110115610261}}),
  DONGBAEK(
      new double[][] {
        {37.2758, 127.1497},
        {37.2779978497857, 127.15160126343},
        {37.2745843001736, 127.150512004189}
      });

  private final double[][] anchors;

  RecommendationArea(double[][] anchors) {
    this.anchors = anchors;
  }

  public static RecommendationArea locate(Double latitude, Double longitude) {
    if (latitude == null
        || longitude == null
        || !Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) return null;
    for (var area : values()) {
      for (var anchor : area.anchors) {
        double dLat = Math.toRadians(latitude - anchor[0]);
        double dLon = Math.toRadians(longitude - anchor[1]);
        double h =
            Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(latitude))
                    * Math.cos(Math.toRadians(anchor[0]))
                    * Math.pow(Math.sin(dLon / 2), 2);
        // Compare before rounding: 500.4m must not become an accepted 500m.
        if (6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h))) <= 500) return area;
      }
    }
    return null;
  }

  public static RecommendationArea of(CandidateData.Product product) {
    return locate(product.latitude(), product.longitude());
  }
}
