package com.togethermarket.product.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.product.entity.*;
import java.math.BigDecimal;
import java.util.List;

public record OwnerProductResponse(
    String id,
    @JsonProperty("store_id") String storeId,
    String name,
    String description,
    @JsonProperty("image_url") String imageUrl,
    @JsonProperty("price_amount") BigDecimal priceAmount,
    @JsonProperty("preparation_minutes") Integer preparationMinutes,
    @JsonProperty("sale_status") SaleStatus saleStatus,
    @JsonProperty("purpose_category_ids") List<String> purposeCategoryIds) {
  public static OwnerProductResponse from(Product p, List<Long> tags) {
    return new OwnerProductResponse(
        p.getId().toString(),
        p.getStoreId().toString(),
        p.getName(),
        p.getDescription(),
        p.getImageUrl(),
        p.getPriceAmount(),
        p.getPreparationMinutes(),
        p.getSaleStatus(),
        tags.stream().sorted().map(Object::toString).toList());
  }
}
