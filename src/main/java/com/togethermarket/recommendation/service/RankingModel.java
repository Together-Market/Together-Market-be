package com.togethermarket.recommendation.service;

import java.util.List;

/** Immutable weights shared with the Python trainer. Scores are not probabilities. */
public record RankingModel(String mode, String version, List<Double> weights) {
  public static final String FEATURE_VERSION = "together-ranker-v1";
  public static final List<String> FEATURE_NAMES =
      List.of("purpose", "taste", "recipient", "complement", "proximity", "budget_remaining");

  public RankingModel {
    if (!List.of("baseline", "trained").contains(mode) || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Invalid ranking model metadata");
    }
    weights = List.copyOf(weights);
    if (weights.size() != 6
        || weights.stream().anyMatch(w -> !Double.isFinite(w))
        || !Double.isFinite(weights.stream().mapToDouble(Math::abs).sum())
        || weights.stream().mapToDouble(Math::abs).sum() < 1e-12) {
      throw new IllegalArgumentException("Six finite, nonzero ranking weights required");
    }
  }

  public static RankingModel baseline() {
    return new RankingModel("baseline", "manual-v1", List.of(25., 25., 15., 20., 10., 5.));
  }

  public double score(List<Double> features) {
    if (features.size() != 6
        || features.stream().anyMatch(v -> v == null || !Double.isFinite(v) || v < 0 || v > 1)) {
      throw new IllegalArgumentException("Six features in [0, 1] required");
    }
    double utility = 0;
    double lower = 0;
    double scale = 0;
    for (int i = 0; i < weights.size(); i++) {
      double weight = weights.get(i);
      utility += weight * features.get(i);
      lower += Math.min(weight, 0);
      scale += Math.abs(weight);
    }
    return 100 * (utility - lower) / scale;
  }
}
