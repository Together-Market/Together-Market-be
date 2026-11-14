package com.togethermarket.recommendation.controller;

import com.togethermarket.global.api.ApiResponse;
import com.togethermarket.global.auth.LoginUser;
import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.dto.response.*;
import com.togethermarket.recommendation.service.customer.CustomerReadAccess;
import com.togethermarket.recommendation.service.persistence.JpaPurchaseCreationService;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.entity.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "recommendation.api.enabled", havingValue = "true")
public class PurchaseController {
  private final PurposeCategoryService categories;
  private final PurposeQuestionService questions;
  private final JpaPurchaseCreationService creation;
  private final PurchaseRequestQueryService queries;
  private final CustomerReadAccess access;

  public PurchaseController(
      PurposeCategoryService categories,
      PurposeQuestionService questions,
      JpaPurchaseCreationService creation,
      PurchaseRequestQueryService queries,
      CustomerReadAccess access) {
    this.categories = categories;
    this.questions = questions;
    this.creation = creation;
    this.queries = queries;
    this.access = access;
  }

  @GetMapping("/purpose-categories")
  public ApiResponse<PurposeCategoriesResponse> categories(
      @AuthenticationPrincipal LoginUser user) {
    if (user == null)
      throw new PurchaseInputException("UNAUTHORIZED", "로그인이 필요합니다.", java.util.List.of());
    if (user.role() != UserRole.CUSTOMER && user.role() != UserRole.OWNER)
      throw new PurchaseInputException("FORBIDDEN", "접근 권한이 없습니다.", java.util.List.of());
    return new ApiResponse<>(categories.categories());
  }

  @GetMapping("/purpose-categories/{category_id}/questions")
  public ApiResponse<PurposeQuestionsResponse> questions(
      @PathVariable("category_id") String id, HttpServletRequest request) {
    access.requireCustomer(request);
    return new ApiResponse<>(questions.questions(id));
  }

  @PostMapping("/purchase-requests")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<PurchaseRequestResponse> create(
      @RequestBody CreatePurchaseRequest input, HttpServletRequest request) {
    return new ApiResponse<>(creation.create(input, access.requireCustomer(request)));
  }

  @GetMapping("/purchase-requests/{request_id}")
  public ApiResponse<PurchaseRequestResponse> get(
      @PathVariable("request_id") String id, HttpServletRequest request) {
    return new ApiResponse<>(queries.get(id, access.requireCustomer(request)));
  }
}
