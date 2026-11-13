package com.togethermarket.recommendation.service.purchase;

import java.util.List;

/** BE3-local failure; BE1's shared exception handler will map this to the HTTP contract. */
public final class PurchaseInputException extends IllegalArgumentException {
  private final String code;
  private final List<Violation> violations;

  public record Violation(String field, String reason) {}

  public PurchaseInputException(String code, String message, List<Violation> violations) {
    super(message);
    this.code = code;
    this.violations = List.copyOf(violations);
  }

  public static PurchaseInputException invalid(String field, String reason) {
    return new PurchaseInputException(
        "VALIDATION_ERROR", "입력값을 확인해 주세요.", List.of(new Violation(field, reason)));
  }

  public String code() {
    return code;
  }

  public List<Violation> violations() {
    return violations;
  }
}
