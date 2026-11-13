package com.togethermarket.global.api;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(ApiError.of(e.code(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                "VALIDATION_ERROR",
                "입력값을 확인해 주세요.",
                e.getBindingResult().getFieldErrors().stream()
                    .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                    .toList()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest()
        .body(ApiError.of("VALIDATION_ERROR", "JSON 객체 형식으로 입력해 주세요."));
  }
}
