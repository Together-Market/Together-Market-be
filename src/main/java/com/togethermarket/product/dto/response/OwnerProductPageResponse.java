package com.togethermarket.product.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OwnerProductPageResponse(
    List<OwnerProductResponse> items,
    int page,
    int size,
    @JsonProperty("total_count") long totalCount) {}
