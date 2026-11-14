package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.product.entity.SaleStatus;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CustomerProductResponse(
    String id,
    @JsonProperty("store_id") String storeId,
    String name,
    String description,
    @JsonProperty("image_url") String imageUrl,
    @JsonProperty("price_amount") long priceAmount,
    @JsonProperty("preparation_minutes") int preparationMinutes,
    @JsonProperty("sale_status") SaleStatus saleStatus,
    @JsonProperty("purpose_category_ids") List<String> purposeCategoryIds,
    CustomerStoreSummary store) {
  public CustomerProductResponse {
    purposeCategoryIds = List.copyOf(purposeCategoryIds);
  }
}
