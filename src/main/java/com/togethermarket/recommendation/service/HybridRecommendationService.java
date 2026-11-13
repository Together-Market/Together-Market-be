package com.togethermarket.recommendation.service;

import com.togethermarket.recommendation.service.RecommendationInput.Candidate;
import com.togethermarket.recommendation.service.RecommendationInput.Context;
import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ranks up to 12 validated snapshots. Caller owns authorization, DB and revalidation. */
public final class HybridRecommendationService {
  private final CandidateEvaluator evaluator;
  private final RankingModel model;

  public HybridRecommendationService(CandidateEvaluator evaluator, RankingModel model) {
    this.evaluator = java.util.Objects.requireNonNull(evaluator);
    this.model = java.util.Objects.requireNonNull(model);
  }

  public record RankedCandidate(
      Candidate candidate,
      String reason,
      double score,
      String ranker_mode,
      String ranker_version) {}

  public List<RankedCandidate> recommend(Context context, List<Candidate> input) {
    if (input.size() > 12) {
      throw new IllegalArgumentException("Preselect at most 12 candidates before AI evaluation");
    }
    Set<String> ids = new HashSet<>();
    Set<List<String>> pairs = new HashSet<>();
    for (Candidate candidate : input) {
      var pair = candidate.products().stream().map(p -> p.id()).sorted().toList();
      if (!ids.add(candidate.candidate_id()) || !pairs.add(pair)) {
        throw new IllegalArgumentException("Duplicate candidate or product pair");
      }
    }
    var candidates =
        input.stream()
            .filter(
                c -> c.pickup_eligible() && c.products().stream().allMatch(p -> p.is_available()))
            .filter(c -> c.totalAmount() <= context.budget_amount())
            .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    var byId = new HashMap<String, Candidate>();
    candidates.forEach(c -> byId.put(c.candidate_id(), c));
    var seen = new HashSet<String>();
    var remaining = new ArrayList<RankedCandidate>();
    for (Evaluation evaluation : evaluator.evaluate(context, candidates)) {
      Candidate candidate = byId.get(evaluation.candidate_id());
      if (candidate == null || !seen.add(evaluation.candidate_id())) {
        throw new IllegalArgumentException("AI returned unknown or duplicate candidate");
      }
      var productIds = candidate.products().stream().map(p -> p.id()).toList();
      if (new HashSet<>(evaluation.evidence_product_ids()).size()
              != evaluation.evidence_product_ids().size()
          || !productIds.containsAll(evaluation.evidence_product_ids())) {
        throw new IllegalArgumentException("AI evidence contains unrelated product IDs");
      }
      if (evaluation.eligible()) {
        remaining.add(
            new RankedCandidate(
                candidate,
                evaluation.reason(),
                model.score(features(context, candidate, evaluation)),
                model.mode(),
                model.version()));
      }
    }
    if (!seen.equals(byId.keySet())) {
      throw new IllegalArgumentException("AI omitted a candidate");
    }
    var selected = new ArrayList<RankedCandidate>();
    while (!remaining.isEmpty() && selected.size() < 6) {
      var best =
          remaining.stream()
              .min(
                  Comparator.<RankedCandidate>comparingDouble(c -> -adjusted(c, selected))
                      .thenComparing(c -> c.candidate().candidate_id()))
              .orElseThrow();
      selected.add(best);
      remaining.remove(best);
    }
    return List.copyOf(selected);
  }

  public static List<Double> features(Context context, Candidate candidate, Evaluation evaluation) {
    double proximity =
        candidate.distance_meters() == null
            ? 0
            : Math.max(0, 1 - candidate.distance_meters() / 1500);
    return List.of(
        evaluation.purpose_score() / 5.0,
        evaluation.taste_score() / 5.0,
        evaluation.recipient_score() / 5.0,
        evaluation.complement_score() / 5.0,
        proximity,
        1 - (double) candidate.totalAmount() / context.budget_amount());
  }

  private static double adjusted(RankedCandidate candidate, List<RankedCandidate> selected) {
    int sharedProducts = 0;
    int sharedStores = 0;
    for (RankedCandidate previous : selected) {
      var products = previous.candidate().products().stream().map(p -> p.id()).toList();
      var stores = previous.candidate().products().stream().map(p -> p.store_id()).toList();
      sharedProducts =
          Math.max(
              sharedProducts,
              (int)
                  candidate.candidate().products().stream()
                      .filter(p -> products.contains(p.id()))
                      .count());
      sharedStores =
          Math.max(
              sharedStores,
              (int)
                  candidate.candidate().products().stream()
                      .filter(p -> stores.contains(p.store_id()))
                      .count());
    }
    return candidate.score() - 15 * sharedProducts - 5 * sharedStores;
  }
}
