package com.togethermarket.recommendation.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.service.purchase.PurchaseRequestSource.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PurchaseRequestQueryServiceTest {
  private Snapshot snapshot(String status, String code) {
    return new Snapshot(
        9_007_199_254_740_993L,
        1L,
        11L,
        60000,
        OffsetDateTime.parse("2026-11-14T15:00:00+09:00"),
        List.of(),
        status,
        OffsetDateTime.parse("2026-11-13T18:00:00+09:00"),
        code);
  }

  @Test
  void checksAuthenticationBeforeReadingAndOwnershipBeforeReturning() {
    AtomicInteger reads = new AtomicInteger();
    var service =
        new PurchaseRequestQueryService(
            id -> {
              reads.incrementAndGet();
              return Optional.of(snapshot("READY", null));
            });
    assertThatThrownBy(() -> service.get("9007199254740993", null))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    assertThat(reads.get()).isZero();
    assertThatThrownBy(() -> service.get("9007199254740993", 2L))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    assertThat(reads.get()).isEqualTo(1);
  }

  @Test
  void missingAndMalformedIdsRemainDifferent() {
    var service = new PurchaseRequestQueryService(id -> Optional.empty());
    assertThatThrownBy(() -> service.get("0", 1L))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> service.get("100", 1L))
        .isInstanceOfSatisfying(
            PurchaseInputException.class,
            e -> assertThat(e.code()).isEqualTo("PURCHASE_REQUEST_NOT_FOUND"));
  }

  @Test
  void serializesLargeIdsAsStringsMoneyAsNumberAndFailureAsExplicitNull() {
    var service = new PurchaseRequestQueryService(id -> Optional.of(snapshot("READY", null)));
    JsonMapper mapper = new JsonMapper();
    var payload = service.get("9007199254740993", 1L);
    Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(payload), Map.class);
    assertThat(json.get("id")).isEqualTo("9007199254740993");
    assertThat(json.get("customer_id")).isEqualTo("1");
    assertThat(json.get("purpose_category_id")).isEqualTo("11");
    assertThat(json.get("budget_amount")).isInstanceOf(Number.class);
    assertThat(json.get("preferred_pickup_at")).isEqualTo("2026-11-14T15:00:00+09:00");
    assertThat(json.get("created_at")).isEqualTo("2026-11-13T18:00:00+09:00");
    assertThat(json.containsKey("failure_code")).isTrue();
    assertThat(json.get("failure_code")).isNull();
    assertThat(json.containsKey("data")).isFalse(); // BE1 supplies the shared envelope.
  }

  @Test
  void failedIsAReadableSnapshotNotAQueryFailure() {
    var service =
        new PurchaseRequestQueryService(id -> Optional.of(snapshot("FAILED", "AI_TIMEOUT")));
    var response = service.get("9007199254740993", 1L);
    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.failureCode()).isEqualTo("AI_TIMEOUT");
    assertThatThrownBy(() -> snapshot("READY", "AI_TIMEOUT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot("FAILED", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot("FAILED", "raw exception with credentials"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestToValidationToReadPreservesSubmittedLabelSnapshot() {
    var reference = PurposeReferenceData.loadDraft();
    var categories = new PurposeCategoryService(reference::categories);
    var clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC);
    var validator =
        new PurchaseConditionValidator(
            new PurposeQuestionService(categories, Map.of(11L, "제출 당시 질문")), clock);
    var input =
        new CreatePurchaseRequest(
            "11",
            60000L,
            "2026-11-14T15:00:00+09:00",
            List.of(
                new CreatePurchaseRequest.Answer("style", "따뜻함"),
                new CreatePurchaseRequest.Answer("recipient", "친구"),
                new CreatePurchaseRequest.Answer("category_preference", "꽃과 디저트")));
    var validated = validator.validate(input);
    var stored =
        new Snapshot(
            100L,
            7L,
            validated.purposeCategoryId(),
            validated.budgetAmount(),
            validated.preferredPickupAt(),
            validated.answers(),
            "RECOMMENDING",
            clock.instant().atOffset(ZoneOffset.UTC),
            null);
    var updatedQuestions = new PurposeQuestionService(categories, Map.of(11L, "변경된 질문"));
    assertThat(updatedQuestions.questions("11").questions().getLast().label()).isEqualTo("변경된 질문");
    var response = new PurchaseRequestQueryService(id -> Optional.of(stored)).get("100", 7L);
    assertThat(response.answers().getLast().label()).isEqualTo("제출 당시 질문");
    assertThat(response.customerId()).isEqualTo(7L);
    assertThat(response.status()).isEqualTo("RECOMMENDING");
  }
}
