package com.togethermarket.recommendation.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest.Answer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PurchaseConditionValidatorTest {
  private static final String PICKUP = "2026-11-14T15:00:00+09:00";
  private final PurposeReferenceData reference = PurposeReferenceData.loadDraft();
  private final PurposeQuestionService questions =
      new PurposeQuestionService(
          new PurposeCategoryService(reference::categories), Map.of(11L, "서버 질문 스냅샷"));
  private final PurchaseConditionValidator validator =
      new PurchaseConditionValidator(
          questions, Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC));

  private List<Answer> validAnswers() {
    return List.of(
        new Answer("category_preference", "꽃과 디저트"),
        new Answer("recipient", "친구"),
        new Answer("style", "아기자기함"));
  }

  private CreatePurchaseRequest request(Long budget, String pickup, List<Answer> answers) {
    return new CreatePurchaseRequest("11", budget, pickup, answers);
  }

  @Test
  void validatesAndSnapshotsServerLabelsInQuestionOrderWithoutBudgetAnswer() {
    var result = validator.validate(request(60000L, PICKUP, validAnswers()));
    assertThat(result.answers())
        .extracting(a -> a.questionCode())
        .containsExactly("style", "recipient", "category_preference");
    assertThat(result.answers().getLast().label()).isEqualTo("서버 질문 스냅샷");
    assertThat(result.budgetAmount()).isEqualTo(60000);
    assertThat(result.preferredPickupAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(result.validatedAt()).isEqualTo(Instant.parse("2026-11-13T09:00:00Z"));
    assertThat(result.purposeCategoryName()).isEqualTo("생일");
    assertThat(result.answerValues()).doesNotContainKey("budget");
    assertThatThrownBy(() -> result.answers().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void budgetBoundsAndUnicodeCharacterLimitAreEnforced() {
    validator.validate(request(1L, PICKUP, validAnswers()));
    validator.validate(request(PurchaseRules.MAX_BUDGET, PICKUP, validAnswers()));
    for (Long budget : Arrays.asList(null, 0L, -1L, PurchaseRules.MAX_BUDGET + 1)) {
      assertThatThrownBy(() -> validator.validate(request(budget, PICKUP, validAnswers())))
          .isInstanceOf(PurchaseInputException.class);
    }
    var answers =
        List.of(
            new Answer("style", "🌸".repeat(1000)),
            new Answer("recipient", "친구"),
            new Answer("category_preference", "꽃"));
    assertThat(validator.validate(request(1L, PICKUP, answers)).answers().getFirst().value())
        .hasSize(2000);
    var tooLong =
        List.of(
            new Answer("style", "🌸".repeat(1001)),
            new Answer("recipient", "친구"),
            new Answer("category_preference", "꽃"));
    assertThatThrownBy(() -> validator.validate(request(1L, PICKUP, tooLong)))
        .isInstanceOf(PurchaseInputException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-11-13T09:00:00Z",
        "2026-11-13T18:00:00+09:00",
        "2026-11-13T08:59:59Z",
        "2026-11-14T15:00:00",
        "invalid",
        "2026-02-30T12:00:00+09:00"
      })
  void rejectsPastEqualOrOffsetlessPickup(String pickup) {
    assertThatThrownBy(() -> validator.validate(request(60000L, pickup, validAnswers())))
        .isInstanceOfSatisfying(
            PurchaseInputException.class,
            e -> assertThat(e.violations()).anyMatch(v -> v.field().equals("preferred_pickup_at")));
  }

  @Test
  void sameFutureInstantWithDifferentOffsetsHasSameResult() {
    var local = validator.validate(request(60000L, PICKUP, validAnswers()));
    var utc = validator.validate(request(60000L, "2026-11-14T06:00:00Z", validAnswers()));
    assertThat(local.preferredPickupAt().toInstant())
        .isEqualTo(utc.preferredPickupAt().toInstant());
  }

  @Test
  void rejectsMissingDuplicateUnknownBudgetAndNullAnswers() {
    List<List<Answer>> cases =
        Arrays.asList(
            null,
            List.of(),
            Arrays.asList((Answer) null),
            List.of(
                new Answer("style", " "),
                new Answer("recipient", "친구"),
                new Answer("category_preference", "꽃")),
            List.of(
                new Answer("style", "좋음"),
                new Answer("style", "중복"),
                new Answer("recipient", "친구"),
                new Answer("category_preference", "꽃")),
            List.of(new Answer("unknown", "값")),
            List.of(new Answer("budget", "60000")),
            List.of(
                new Answer("style", null),
                new Answer("recipient", "친구"),
                new Answer("category_preference", "꽃")));
    for (var answers : cases) {
      assertThatThrownBy(() -> validator.validate(request(60000L, PICKUP, answers)))
          .isInstanceOf(PurchaseInputException.class);
    }
  }

  @Test
  void optionalExclusionMayBeOmittedOrExplicitlyEmpty() {
    var answers = new java.util.ArrayList<>(validAnswers());
    answers.add(new Answer("excluded_products", ""));
    assertThat(validator.validate(request(60000L, PICKUP, answers)).answers())
        .anyMatch(a -> a.questionCode().equals("excluded_products") && a.value().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "+11", "1.0", " 11", "abc", "9223372036854775808"})
  void rejectsInvalidBigintIds(String id) {
    assertThatThrownBy(
            () -> validator.validate(new CreatePurchaseRequest(id, 60000L, PICKUP, validAnswers())))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
  }

  private String json(String budget) {
    return "{\"purpose_category_id\":\"11\",\"budget_amount\":"
        + budget
        + ",\"preferred_pickup_at\":\""
        + PICKUP
        + "\",\"answers\":["
        + "{\"question_code\":\"style\",\"value\":\"아기자기함\"},"
        + "{\"question_code\":\"recipient\",\"value\":\"친구\"},"
        + "{\"question_code\":\"category_preference\",\"value\":\"꽃\"}]}";
  }

  @Test
  void strictJsonParsingWorksWithoutChangingGlobalMapper() {
    JsonMapper mapper = new JsonMapper();
    var request = mapper.readValue(json("60000"), CreatePurchaseRequest.class);
    assertThat(validator.validate(request).budgetAmount()).isEqualTo(60000);
    for (String malformed :
        List.of(
            json("\"60000\""),
            json("60000.0"),
            json("6e4"),
            json("null"),
            json("true"),
            json("9223372036854775808"),
            json("60000").replace("\"11\"", "11"),
            json("60000").replace("\"answers\":", "\"customer_id\":\"99\",\"answers\":"),
            json("60000").replace("\"value\":\"꽃\"", "\"value\":\"꽃\",\"label\":\"클라이언트 위조\""),
            json("60000").replace("\"value\":\"꽃\"", "\"value\":123"))) {
      assertThatThrownBy(() -> mapper.readValue(malformed, CreatePurchaseRequest.class))
          .as(malformed)
          .isInstanceOf(RuntimeException.class);
    }
  }
}
