package com.togethermarket.global.api;

import java.util.List;

public record ApiError(String code, String message, List<FieldError> errors) {
  public record FieldError(String field, String reason) {}

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, List.of());
  }
}
