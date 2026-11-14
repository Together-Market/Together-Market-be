package com.togethermarket.recommendation.controller;

import com.togethermarket.global.api.ApiResponse;
import com.togethermarket.recommendation.dto.response.RecommendationResponse;
import com.togethermarket.recommendation.service.customer.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "recommendation.api.enabled", havingValue = "true")
public class RecommendationController {
  private final RecommendationApiService service;
  private final CustomerReadAccess access;

  public RecommendationController(RecommendationApiService service, CustomerReadAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/purchase-requests/{request_id}/recommendations")
  public ApiResponse<RecommendationResponse.Page> list(
      @PathVariable("request_id") String id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    return new ApiResponse<>(service.list(id, access.requireCustomer(request), page, size));
  }

  @GetMapping("/recommendations/{candidate_id}")
  public ApiResponse<RecommendationResponse> detail(
      @PathVariable("candidate_id") String id, HttpServletRequest request) {
    return new ApiResponse<>(service.detail(id, access.requireCustomer(request)));
  }

  @GetMapping("/reservations/{reservation_id}/alternatives")
  public ApiResponse<RecommendationResponse.Alternatives> alternatives(
      @PathVariable("reservation_id") String id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    return new ApiResponse<>(service.alternatives(id, access.requireCustomer(request), page, size));
  }
}
