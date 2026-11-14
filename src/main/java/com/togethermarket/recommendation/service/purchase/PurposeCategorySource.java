package com.togethermarket.recommendation.service.purchase;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** BE3 read boundary. Implement against the team's shared repository after it is provided. */
@FunctionalInterface
public interface PurposeCategorySource {
  /** Include inactive entries so missing and unusable categories can be distinguished. */
  List<Category> findAll();

  record Category(
      Long id,
      @JsonProperty("parent_id") Long parentId,
      String name,
      @JsonProperty("display_order") int displayOrder,
      boolean active) {
    public Category {
      if (id == null
          || id <= 0
          || (parentId != null && parentId <= 0)
          || name == null
          || name.isBlank()) {
        throw new IllegalArgumentException("Invalid category snapshot");
      }
    }
  }
}
