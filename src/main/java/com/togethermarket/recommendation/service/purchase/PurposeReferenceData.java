package com.togethermarket.recommendation.service.purchase;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Reference for seed handoff; draft question labels are never auto-registered as beans. */
public record PurposeReferenceData(
    String status,
    List<PurposeCategorySource.Category> categories,
    @JsonProperty("category_preference_labels") Map<Long, String> categoryPreferenceLabels) {
  public PurposeReferenceData {
    categories = List.copyOf(categories);
    categoryPreferenceLabels = Map.copyOf(categoryPreferenceLabels);
  }

  public static PurposeReferenceData loadDraft() {
    try (var stream =
        PurposeReferenceData.class.getResourceAsStream("/recommendation/purchase-reference.json")) {
      if (stream == null) {
        throw new IllegalStateException("Missing purchase reference data");
      }
      return new JsonMapper().readValue(stream, PurposeReferenceData.class);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read purchase reference data", exception);
    }
  }
}
