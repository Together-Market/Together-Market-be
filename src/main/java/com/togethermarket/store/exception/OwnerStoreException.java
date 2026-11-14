package com.togethermarket.store.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

public class OwnerStoreException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final List<FieldError> errors;

  public record FieldError(String field, String reason) {}

  public OwnerStoreException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  private OwnerStoreException(
      HttpStatus status, String code, String message, List<FieldError> errors) {
    super(message);
    this.status = status;
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  public static OwnerStoreException invalid(String field, String reason) {
    return new OwnerStoreException(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "입력값을 확인해 주세요.",
        List.of(new FieldError(field, reason)));
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<FieldError> errors() {
    return errors;
  }
}
