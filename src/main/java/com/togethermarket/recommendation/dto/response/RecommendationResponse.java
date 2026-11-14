package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.product.entity.SaleStatus;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendationResponse(
    String id,
    @JsonProperty("purchase_request_id") String purchaseRequestId,
    int rank,
    @JsonProperty("recommendation_reason") String recommendationReason,
    @JsonProperty("total_amount") long totalAmount,
    @JsonProperty("availability_status") String availabilityStatus,
    @JsonProperty("unavailable_reasons") List<Reason> unavailableReasons,
    List<Item> items,
    @JsonProperty("store_hours") @JsonInclude(JsonInclude.Include.NON_NULL)
        List<Hours> storeHours) {
  public record Reason(@JsonProperty("product_id") String productId, String code) {}

  public record Product(
      String id,
      @JsonProperty("store_id") String storeId,
      String name,
      String description,
      @JsonProperty("image_url") String imageUrl,
      @JsonProperty("price_amount") long priceAmount,
      @JsonProperty("preparation_minutes") int preparationMinutes,
      @JsonProperty("sale_status") SaleStatus saleStatus,
      @JsonProperty("purpose_category_ids") List<String> purposeCategoryIds) {
    public static Product from(CustomerProductResponse p) {
      return new Product(
          p.id(),
          p.storeId(),
          p.name(),
          p.description(),
          p.imageUrl(),
          p.priceAmount(),
          p.preparationMinutes(),
          p.saleStatus(),
          p.purposeCategoryIds());
    }
  }

  public record Item(
      String id,
      Product product,
      CustomerStoreSummary store,
      int quantity,
      @JsonProperty("unit_price_snapshot") long unitPriceSnapshot,
      @JsonProperty("current_unit_price") long currentUnitPrice) {}

  public record Hours(
      @JsonProperty("store_id") String storeId, List<CustomerStoreResponse.Hour> items) {}

  public record Page(
      @JsonProperty("purchase_request_id") String purchaseRequestId,
      String status,
      String message,
      List<RecommendationResponse> items,
      int page,
      int size,
      @JsonProperty("total_count") long totalCount) {}

  public record Alternatives(
      @JsonProperty("reservation_id") String reservationId,
      List<RecommendationResponse> items,
      String message,
      int page,
      int size,
      @JsonProperty("total_count") long totalCount) {}
}
