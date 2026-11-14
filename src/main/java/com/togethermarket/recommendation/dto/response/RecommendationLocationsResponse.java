package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendationLocationsResponse(
    @JsonProperty("recommendation_candidate_id") String recommendationCandidateId,
    List<CustomerStoreSummary> stores,
    @JsonProperty("distance_meters") Long distanceMeters,
    @JsonProperty("distance_type") String distanceType,
    @JsonProperty("location_status") String locationStatus) {
  public RecommendationLocationsResponse {
    stores = List.copyOf(stores);
  }
}
