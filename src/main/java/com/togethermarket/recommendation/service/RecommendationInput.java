package com.togethermarket.recommendation.service;

import java.util.List;
import java.util.Map;

/** Internal adapter DTOs, not public endpoints or replacements for BE1/BE2 entities. */
public final class RecommendationInput {
  private RecommendationInput() {}

  public record Context(
      String purpose, long budget_amount, String preferred_pickup_at, Map<String, String> answers) {
    public Context {
      if (purpose == null || purpose.isBlank() || budget_amount <= 0) {
        throw new IllegalArgumentException("Purpose and positive budget required");
      }
      java.time.OffsetDateTime.parse(preferred_pickup_at);
      answers = Map.copyOf(answers);
    }
  }

  public record Product(
      String id,
      String store_id,
      String name,
      String description,
      String business_type,
      long price_amount,
      boolean is_available) {
    public Product {
      if (id == null
          || id.isBlank()
          || store_id == null
          || store_id.isBlank()
          || name == null
          || name.isBlank()
          || description == null
          || business_type == null
          || price_amount <= 0) {
        throw new IllegalArgumentException("Invalid product snapshot");
      }
    }
  }

  /** pickup_eligible must come from BE2 checks for this request's exact pickup time. */
  public record Candidate(
      String candidate_id,
      List<Product> products,
      Double distance_meters,
      boolean pickup_eligible) {
    public Candidate {
      products = List.copyOf(products);
      if (candidate_id == null
          || candidate_id.isBlank()
          || products.size() != 2
          || products.get(0).id().equals(products.get(1).id())
          || products.get(0).store_id().equals(products.get(1).store_id())
          || (distance_meters != null
              && (!Double.isFinite(distance_meters) || distance_meters < 0))) {
        throw new IllegalArgumentException("Invalid two-store candidate");
      }
      Math.addExact(products.get(0).price_amount(), products.get(1).price_amount());
    }

    public long totalAmount() {
      return Math.addExact(products.get(0).price_amount(), products.get(1).price_amount());
    }
  }

  public record Evaluation(
      String candidate_id,
      int purpose_score,
      int taste_score,
      int recipient_score,
      int complement_score,
      String exclusion_status,
      List<String> evidence_product_ids,
      String reason) {
    public Evaluation {
      evidence_product_ids = List.copyOf(evidence_product_ids);
      if (candidate_id == null
          || candidate_id.isBlank()
          || List.of(purpose_score, taste_score, recipient_score, complement_score).stream()
              .anyMatch(s -> s < 0 || s > 5)
          || !List.of("ALLOWED", "EXCLUDED", "UNKNOWN").contains(exclusion_status)
          || reason == null
          || reason.isBlank()
          || reason.codePointCount(0, reason.length()) > 120
          || evidence_product_ids.isEmpty()) {
        throw new IllegalArgumentException("Invalid AI evaluation");
      }
    }

    public boolean eligible() {
      return exclusion_status.equals("ALLOWED") && purpose_score >= 2 && complement_score >= 2;
    }
  }
}
