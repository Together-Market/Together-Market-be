package com.togethermarket.recommendation.service.purchase;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse.Answer;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException.Violation;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Validates input and takes server-side label snapshots without persisting or starting AI work. */
public final class PurchaseConditionValidator {
  private final PurposeQuestionService questions;
  private final Clock clock;

  public PurchaseConditionValidator(PurposeQuestionService questions, Clock clock) {
    this.questions = Objects.requireNonNull(questions);
    this.clock = Objects.requireNonNull(clock);
  }

  public record ValidatedConditions(
      Long purposeCategoryId,
      String purposeCategoryName,
      long budgetAmount,
      OffsetDateTime preferredPickupAt,
      List<Answer> answers,
      Instant validatedAt) {
    public ValidatedConditions {
      answers = List.copyOf(answers);
    }

    public Map<String, String> answerValues() {
      return answers.stream()
          .collect(Collectors.toUnmodifiableMap(Answer::questionCode, Answer::value));
    }
  }

  public ValidatedConditions validate(CreatePurchaseRequest request) {
    if (request == null) {
      throw PurchaseInputException.invalid("body", "요청 본문이 필요합니다.");
    }
    Long categoryId = PurchaseRules.parseId(request.purposeCategoryId(), "purpose_category_id");
    var definition = questions.definition(categoryId);
    var violations = new ArrayList<Violation>();
    if (request.budgetAmount() == null
        || request.budgetAmount() < PurchaseRules.MIN_BUDGET
        || request.budgetAmount() > PurchaseRules.MAX_BUDGET) {
      violations.add(new Violation("budget_amount", "예산은 1~999999999999원의 정수여야 합니다."));
    }
    Instant now = clock.instant();
    OffsetDateTime pickup = null;
    try {
      if (request.preferredPickupAt() == null) {
        throw new IllegalArgumentException();
      }
      pickup = OffsetDateTime.parse(request.preferredPickupAt());
      if (!pickup.toInstant().isAfter(now)) {
        violations.add(new Violation("preferred_pickup_at", "수령 희망일시는 현재보다 미래여야 합니다."));
      }
    } catch (IllegalArgumentException | DateTimeParseException exception) {
      violations.add(new Violation("preferred_pickup_at", "오프셋을 포함한 ISO 8601 일시가 필요합니다."));
    }
    var textQuestions =
        definition.response().questions().stream().filter(q -> q.type().equals("TEXT")).toList();
    var byCode = textQuestions.stream().collect(Collectors.toMap(q -> q.code(), q -> q));
    var submitted = new HashMap<String, String>();
    if (request.answers() == null) {
      violations.add(new Violation("answers", "답변 배열이 필요합니다."));
    } else {
      if (request.answers().size() > textQuestions.size()) {
        violations.add(new Violation("answers", "질문 수보다 많은 답변은 제출할 수 없습니다."));
      }
      for (int i = 0; i < request.answers().size(); i++) {
        String path = "answers[" + i + "]";
        var answer = request.answers().get(i);
        if (answer == null) {
          violations.add(new Violation(path, "답변 객체가 필요합니다."));
          continue;
        }
        var question = byCode.get(answer.questionCode());
        if (question == null) {
          violations.add(new Violation(path + ".question_code", "조회된 TEXT 질문 코드만 사용할 수 있습니다."));
          continue;
        }
        if (submitted.containsKey(answer.questionCode())) {
          violations.add(new Violation(path + ".question_code", "질문 코드를 중복 제출할 수 없습니다."));
          continue;
        }
        submitted.put(answer.questionCode(), answer.value());
        String value = answer.value();
        if (value == null) {
          violations.add(new Violation(path + ".value", "답변은 null일 수 없습니다."));
        } else if (question.required() && value.isBlank()) {
          violations.add(new Violation(path + ".value", "필수 답변은 공백일 수 없습니다."));
        } else if (value.codePointCount(0, value.length()) > question.maxLength()) {
          violations.add(new Violation(path + ".value", "답변은 1000자 이하여야 합니다."));
        }
      }
    }
    for (var question : textQuestions) {
      if (question.required() && !submitted.containsKey(question.code())) {
        violations.add(new Violation("answers", "필수 질문 답변이 누락되었습니다: " + question.code()));
      }
    }
    if (!violations.isEmpty()) {
      throw new PurchaseInputException("VALIDATION_ERROR", "입력값을 확인해 주세요.", violations);
    }
    var snapshots =
        textQuestions.stream()
            .filter(q -> submitted.containsKey(q.code()))
            .map(q -> new Answer(q.code(), submitted.get(q.code()), q.label()))
            .toList();
    return new ValidatedConditions(
        categoryId, definition.category().name(), request.budgetAmount(), pickup, snapshots, now);
  }
}
