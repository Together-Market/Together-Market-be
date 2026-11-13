package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** The payload inside BE1's shared data envelope. */
public record PurposeCategoriesResponse(List<Category> items) {
  public PurposeCategoriesResponse {
    items = List.copyOf(items);
  }

  public record Category(
      @JsonSerialize(using = ToStringSerializer.class) Long id, String name, List<Child> children) {
    public Category {
      children = List.copyOf(children);
    }
  }

  public record Child(
      @JsonSerialize(using = ToStringSerializer.class) Long id,
      @JsonProperty("parent_id") @JsonSerialize(using = ToStringSerializer.class) Long parentId,
      String name) {}
}
