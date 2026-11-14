package com.togethermarket.recommendation.controller;

import com.togethermarket.global.api.ApiResponse;
import com.togethermarket.recommendation.dto.response.*;
import com.togethermarket.recommendation.service.customer.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "recommendation.customer-read.enabled", havingValue = "true")
public class CustomerReadController {
  private final CustomerReadService service;
  private final CustomerReadAccess access;

  public CustomerReadController(CustomerReadService service, CustomerReadAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/products/{product_id}")
  public ApiResponse<CustomerProductResponse> product(
      @PathVariable("product_id") String id, HttpServletRequest request) {
    return new ApiResponse<>(service.product(id, access.requireCustomer(request)));
  }

  @GetMapping("/stores/{store_id}")
  public ApiResponse<CustomerStoreResponse> store(
      @PathVariable("store_id") String id, HttpServletRequest request) {
    return new ApiResponse<>(service.store(id, access.requireCustomer(request)));
  }

  @GetMapping("/recommendations/{candidate_id}/locations")
  public ApiResponse<RecommendationLocationsResponse> locations(
      @PathVariable("candidate_id") String id, HttpServletRequest request) {
    return new ApiResponse<>(service.locations(id, access.requireCustomer(request)));
  }
}
