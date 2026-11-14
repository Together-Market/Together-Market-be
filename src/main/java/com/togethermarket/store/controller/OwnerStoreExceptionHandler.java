package com.togethermarket.store.controller;

import com.togethermarket.store.exception.OwnerStoreException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
    assignableTypes = {
      OwnerStoreController.class,
      OwnerStoreHoursController.class,
      com.togethermarket.product.controller.OwnerProductController.class
    })
public class OwnerStoreExceptionHandler {
  public record ErrorResponse(
      String code, String message, List<OwnerStoreException.FieldError> errors) {}

  @ExceptionHandler(OwnerStoreException.class)
  ResponseEntity<ErrorResponse> domain(OwnerStoreException ex) {
    return ResponseEntity.status(ex.status())
        .body(new ErrorResponse(ex.code(), ex.getMessage(), ex.errors()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformed(HttpMessageNotReadableException ex) {
    return domain(OwnerStoreException.invalid("body", "JSON 객체 형식으로 입력해 주세요."));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> type(MethodArgumentTypeMismatchException ex) {
    return domain(OwnerStoreException.invalid(ex.getName(), "올바른 형식으로 입력해 주세요."));
  }
}
