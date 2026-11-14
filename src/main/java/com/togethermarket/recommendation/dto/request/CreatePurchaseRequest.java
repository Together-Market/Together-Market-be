package com.togethermarket.recommendation.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict per-request parsing without changing BE1's application-wide Jackson configuration. */
public record CreatePurchaseRequest(
    @JsonProperty("purpose_category_id") String purposeCategoryId,
    @JsonProperty("budget_amount") Long budgetAmount,
    @JsonProperty("preferred_pickup_at") String preferredPickupAt,
    List<Answer> answers) {
  public record Answer(@JsonProperty("question_code") String questionCode, String value) {}

  public CreatePurchaseRequest {
    if (answers != null) {
      answers = Collections.unmodifiableList(new ArrayList<>(answers));
    }
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static CreatePurchaseRequest fromJson(Map<String, Object> body) {
    if (body == null) {
      throw PurchaseInputException.invalid("body", "요청 본문이 필요합니다.");
    }
    rejectUnknown(
        body, Set.of("purpose_category_id", "budget_amount", "preferred_pickup_at", "answers"), "");
    String category = requiredString(body.get("purpose_category_id"), "purpose_category_id");
    Object budget = body.get("budget_amount");
    if (!(budget instanceof Integer) && !(budget instanceof Long)) {
      throw PurchaseInputException.invalid("budget_amount", "예산은 JSON 정수여야 합니다.");
    }
    String pickup = requiredString(body.get("preferred_pickup_at"), "preferred_pickup_at");
    if (!(body.get("answers") instanceof List<?> rawAnswers)) {
      throw PurchaseInputException.invalid("answers", "답변 배열이 필요합니다.");
    }
    if (rawAnswers.size() > 4) {
      throw PurchaseInputException.invalid("answers", "TEXT 질문 답변은 최대 4개입니다.");
    }
    var answers = new ArrayList<Answer>();
    for (int i = 0; i < rawAnswers.size(); i++) {
      String path = "answers[" + i + "]";
      if (!(rawAnswers.get(i) instanceof Map<?, ?> answer)) {
        throw PurchaseInputException.invalid(path, "답변은 객체여야 합니다.");
      }
      rejectUnknown(answer, Set.of("question_code", "value"), path + ".");
      answers.add(
          new Answer(
              requiredString(answer.get("question_code"), path + ".question_code"),
              requiredString(answer.get("value"), path + ".value")));
    }
    return new CreatePurchaseRequest(category, ((Number) budget).longValue(), pickup, answers);
  }

  private static String requiredString(Object value, String field) {
    if (!(value instanceof String text)) {
      throw PurchaseInputException.invalid(field, "문자열 값이 필요합니다.");
    }
    return text;
  }

  private static void rejectUnknown(Map<?, ?> values, Set<String> allowed, String prefix) {
    for (Object key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw PurchaseInputException.invalid(prefix + key, "허용되지 않은 필드입니다.");
      }
    }
  }
}
