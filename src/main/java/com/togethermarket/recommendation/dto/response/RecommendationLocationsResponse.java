package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RecommendationLocationsResponse(
    @JsonProperty("recommendation_candidate_id") String recommendationCandidateId,
    List<CustomerStoreSummary> stores,
    @JsonProperty("distance_meters") long distanceMeters,
    @JsonProperty("distance_type") String distanceType) {
  public RecommendationLocationsResponse {
    stores = List.copyOf(stores);
  }
}
