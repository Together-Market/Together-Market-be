package com.togethermarket.store.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OwnerStorePageResponse(
    List<StoreResponse> items, int page, int size, @JsonProperty("total_count") long totalCount) {}
