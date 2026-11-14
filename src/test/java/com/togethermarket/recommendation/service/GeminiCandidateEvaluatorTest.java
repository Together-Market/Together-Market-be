package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.recommendation.service.RecommendationInput.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeminiCandidateEvaluatorTest {
  @Test
  void usageRecordsOnlyNumericCountersAndDoesNotInventMissingValues() {
    var body =
        RecommendationJson.stringify(
            Map.of(
                "usageMetadata",
                Map.of(
                    "promptTokenCount",
                    1200,
                    "candidatesTokenCount",
                    600,
                    "thoughtsTokenCount",
                    10,
                    "totalTokenCount",
                    1810,
                    "secret",
                    "never-log-me")));
    assertThat(GeminiCandidateEvaluator.usage(body))
        .containsEntry("totalTokenCount", 1810L)
        .hasSize(4);
    assertThat(GeminiCandidateEvaluator.usage("{}")).isEmpty();
  }

  final Context context =
      new Context("생일", 10000, "2026-11-20T15:00:00+09:00", Map.of("style", "따뜻함"));
  final Candidate candidate =
      new Candidate(
          "pair",
          List.of(
              new Product("1", "11", "꽃", "시연", "꽃집", 3000, true),
              new Product("2", "12", "쿠키", "시연", "제과점", 4000, true)),
          null,
          true);

  Map<String, Object> evaluation() {
    return Map.of(
        "candidate_id",
        "pair",
        "purpose_score",
        5,
        "taste_score",
        4,
        "recipient_score",
        4,
        "complement_score",
        5,
        "exclusion_status",
        "ALLOWED",
        "evidence_product_ids",
        List.of("1", "2"),
        "reason",
        "꽃과 쿠키를 함께 선물할 수 있어요.");
  }

  String response(String finish, Map<String, Object> evaluation) {
    return RecommendationJson.stringify(
        Map.of(
            "candidates",
            List.of(
                Map.of(
                    "finishReason",
                    finish,
                    "content",
                    Map.of(
                        "parts",
                        List.of(
                            Map.of("thought", true, "text", "internal thought"),
                            Map.of(
                                "text",
                                RecommendationJson.stringify(
                                    Map.of("evaluations", List.of(evaluation))))))))));
  }

  @Test
  void completedJsonUsesSharedStrictSchemaAndSkipsThoughts() {
    assertThat(
            GeminiCandidateEvaluator.parseResponse(response("STOP", evaluation()))
                .getFirst()
                .candidate_id())
        .isEqualTo("pair");
    var invalid = new HashMap<>(evaluation());
    invalid.put("taste_score", 3.5);
    assertThatThrownBy(() -> GeminiCandidateEvaluator.parseResponse(response("STOP", invalid)))
        .isInstanceOf(IllegalArgumentException.class);
    invalid.put("taste_score", 3);
    invalid.put("unexpected", true);
    assertThatThrownBy(() -> GeminiCandidateEvaluator.parseResponse(response("STOP", invalid)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blockedTruncatedAndEmptyResponsesAreNotRecommendations() {
    for (String finish : List.of("MAX_TOKENS", "SAFETY", "RECITATION"))
      assertThatThrownBy(
              () -> GeminiCandidateEvaluator.parseResponse(response(finish, evaluation())))
          .isInstanceOf(IllegalArgumentException.class);
    for (String body :
        List.of("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", "{\"candidates\":[]}"))
      assertThatThrownBy(() -> GeminiCandidateEvaluator.parseResponse(body))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendsHeaderKeyToFixedHostAndNeverRetriesAuthenticationFailure() throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<String> denied = mock(HttpResponse.class);
    when(denied.statusCode()).thenReturn(403);
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(denied);
    var evaluator = new GeminiCandidateEvaluator("test-key", "gemini-test", client);
    assertThatThrownBy(() -> evaluator.evaluate(context, List.of(candidate)))
        .hasMessage("Gemini HTTP 403; response body omitted");
    var sent = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(1)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(sent.getValue().uri().getHost()).isEqualTo("generativelanguage.googleapis.com");
    assertThat(sent.getValue().uri().getRawQuery()).isNull();
    assertThat(sent.getValue().headers().firstValue("x-goog-api-key")).contains("test-key");
    assertThat(RecommendationJson.stringify(evaluator.requestBody(context, List.of(candidate))))
        .doesNotContain("test-key");
    assertThatThrownBy(
            () -> new GeminiCandidateEvaluator("test-key", "gemini-x/../../other?key=bad"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void retriesOneTransientFailureAndReturnsValidatedResult() throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<String> busy = mock(HttpResponse.class), ok = mock(HttpResponse.class);
    when(busy.statusCode()).thenReturn(503);
    when(ok.statusCode()).thenReturn(200);
    when(ok.body()).thenReturn(response("STOP", evaluation()));
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(busy, ok);
    assertThat(
            new GeminiCandidateEvaluator("test-key", "gemini-test", client)
                .evaluate(context, List.of(candidate)))
        .hasSize(1);
    verify(client, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void payloadMatchesPythonGoldenContract() throws Exception {
    try (var input = getClass().getResourceAsStream("/recommendation/gemini-contract.json")) {
      var golden =
          RecommendationJson.parse(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      var payload =
          new GeminiCandidateEvaluator("test-key", "gemini-test")
              .requestBody(context, List.of(candidate));
      assertThat(payload.get("systemInstruction")).isEqualTo(golden.get("systemInstruction"));
      assertThat(payload.get("generationConfig")).isEqualTo(golden.get("generationConfig"));
      var content =
          RecommendationJson.object(RecommendationJson.array(payload.get("contents")).getFirst());
      var part =
          RecommendationJson.object(RecommendationJson.array(content.get("parts")).getFirst());
      var expectedContent =
          RecommendationJson.object(RecommendationJson.array(golden.get("contents")).getFirst());
      var expectedPart =
          RecommendationJson.object(
              RecommendationJson.array(expectedContent.get("parts")).getFirst());
      assertThat(RecommendationJson.parse((String) part.get("text")))
          .isEqualTo(RecommendationJson.parse((String) expectedPart.get("text")));
    }
  }
}
