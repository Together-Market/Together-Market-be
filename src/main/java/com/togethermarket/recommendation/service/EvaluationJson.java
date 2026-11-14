package com.togethermarket.recommendation.service;

import static com.togethermarket.recommendation.service.RecommendationJson.*;

import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Identical strict result schema for all AI transports. */
final class EvaluationJson {
  private EvaluationJson() {}

  static List<Evaluation> parse(String json) {
    var parsed = RecommendationJson.parse(json);
    if (!parsed.keySet().equals(Set.of("evaluations"))) {
      throw new IllegalArgumentException("Unexpected AI output fields");
    }
    var evaluations = new ArrayList<Evaluation>();
    for (Object value : array(parsed.get("evaluations"))) {
      var item = object(value);
      if (!item.keySet()
          .equals(
              Set.of(
                  "candidate_id",
                  "purpose_score",
                  "taste_score",
                  "recipient_score",
                  "complement_score",
                  "exclusion_status",
                  "evidence_product_ids",
                  "reason"))) {
        throw new IllegalArgumentException("Unexpected evaluation fields");
      }
      evaluations.add(
          new Evaluation(
              string(item.get("candidate_id")),
              integer(item.get("purpose_score")),
              integer(item.get("taste_score")),
              integer(item.get("recipient_score")),
              integer(item.get("complement_score")),
              string(item.get("exclusion_status")),
              array(item.get("evidence_product_ids")).stream()
                  .map(RecommendationJson::string)
                  .toList(),
              string(item.get("reason"))));
    }
    return List.copyOf(evaluations);
  }
}
