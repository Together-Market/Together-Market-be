package com.togethermarket.recommendation.service.purchase;

public final class PurchaseRules {
  public static final long MIN_BUDGET = 1;
  public static final long MAX_BUDGET = 999_999_999_999L;
  public static final int MAX_ANSWER_LENGTH = 1000;

  private PurchaseRules() {}

  public static Long parseId(String value, String field) {
    try {
      if (value == null || value.isEmpty() || !value.matches("[0-9]+")) {
        throw new NumberFormatException();
      }
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException exception) {
      throw PurchaseInputException.invalid(field, "ID는 양의 BIGINT 범위 정수 문자열이어야 합니다.");
    }
  }
}
