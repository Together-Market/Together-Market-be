package com.togethermarket.recommendation.controller;

import com.togethermarket.global.api.ApiError;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(
    assignableTypes = {
      PurchaseController.class,
      CustomerReadController.class,
      RecommendationController.class
    })
public class RecommendationExceptionHandler {
  @ExceptionHandler(PurchaseInputException.class)
  public ResponseEntity<ApiError> domain(PurchaseInputException e) {
    int status =
        switch (e.code()) {
          case "UNAUTHORIZED" -> 401;
          case "FORBIDDEN" -> 403;
          case "VALIDATION_ERROR",
              "INVALID_PURPOSE_CATEGORY",
              "INVALID_ANSWERS",
              "INVALID_PICKUP_TIME" ->
              400;
          case "PURPOSE_CATEGORY_NOT_FOUND",
              "PURCHASE_REQUEST_NOT_FOUND",
              "RECOMMENDATION_NOT_FOUND",
              "RESERVATION_NOT_FOUND",
              "PRODUCT_NOT_FOUND",
              "STORE_NOT_FOUND" ->
              404;
          case "RECOMMENDATION_IN_PROGRESS",
              "RECOMMENDATION_FAILED",
              "RESERVATION_NOT_FAILED",
              "RECOMMENDATION_DATA_UNAVAILABLE",
              "STORE_LOCATION_UNAVAILABLE" ->
              409;
          case "RECOMMENDATION_SERVICE_UNAVAILABLE" -> 503;
          default -> 500;
        };
    if (status == 500)
      return ResponseEntity.internalServerError()
          .body(ApiError.of("INTERNAL_SERVER_ERROR", "요청 처리 중 오류가 발생했습니다."));
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                e.code(),
                e.getMessage(),
                e.violations().stream()
                    .map(v -> new ApiError.FieldError(v.field(), v.reason()))
                    .toList()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause())
      if (cause instanceof PurchaseInputException p) return domain(p);
    return ResponseEntity.badRequest()
        .body(ApiError.of("VALIDATION_ERROR", "JSON 객체 형식으로 입력해 주세요."));
  }
}
