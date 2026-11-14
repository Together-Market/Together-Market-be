package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiCandidateEvaluatorTest {
  private Map<String, Object> evaluation() {
    return Map.of(
        "candidate_id",
        "1",
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
        List.of("11", "12"),
        "reason",
        "검증한 상품의 테스트 설명입니다.");
  }

  private String response(Map<String, Object> evaluation) {
    return RecommendationJson.stringify(
        Map.of(
            "status",
            "completed",
            "output",
            List.of(
                Map.of(
                    "type",
                    "message",
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "output_text",
                            "text",
                            RecommendationJson.stringify(
                                Map.of("evaluations", List.of(evaluation)))))))));
  }

  @Test
  void parsesCompletedStructuredOutput() {
    var result = OpenAiCandidateEvaluator.parseResponse(response(evaluation()));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().candidate_id()).isEqualTo("1");
  }

  @Test
  void rejectsIncompleteRefusalWrongTypesAndExtraKeys() {
    assertThatThrownBy(() -> OpenAiCandidateEvaluator.parseResponse("{\"status\":\"incomplete\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    String refusal =
        RecommendationJson.stringify(
            Map.of(
                "status",
                "completed",
                "output",
                List.of(
                    Map.of(
                        "type",
                        "message",
                        "content",
                        List.of(Map.of("type", "refusal", "refusal", "no"))))));
    assertThatThrownBy(() -> OpenAiCandidateEvaluator.parseResponse(refusal))
        .isInstanceOf(IllegalArgumentException.class);
    var bad = new HashMap<>(evaluation());
    bad.put("purpose_score", 3.5);
    assertThatThrownBy(() -> OpenAiCandidateEvaluator.parseResponse(response(bad)))
        .isInstanceOf(IllegalArgumentException.class);
    bad.put("purpose_score", 5);
    bad.put("invented", true);
    assertThatThrownBy(() -> OpenAiCandidateEvaluator.parseResponse(response(bad)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void modelLoaderChecksVersionsAndRejectsTestArtifacts() {
    var artifact = new HashMap<String, Object>();
    artifact.put("artifact_version", 1);
    artifact.put("mode", "trained");
    artifact.put("ranker_version", "unit-test");
    artifact.put("feature_version", RankingModel.FEATURE_VERSION);
    artifact.put("feature_names", RankingModel.FEATURE_NAMES);
    artifact.put("weights", List.of(1, 1, 1, 1, 1, 1));
    artifact.put("prompt_version", RecommendationJson.promptVersion());
    artifact.put("api_model", "unit-test-model");
    artifact.put("training_scenarios", 2);
    artifact.put("training_comparisons", 6);
    assertThat(
            RankingModelLoader.load(RecommendationJson.stringify(artifact), "unit-test-model")
                .mode())
        .isEqualTo("trained");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(RecommendationJson.stringify(artifact), "different-model"))
        .isInstanceOf(IllegalArgumentException.class);
    artifact.put("mode", "fixture");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(RecommendationJson.stringify(artifact), "unit-test-model"))
        .isInstanceOf(IllegalArgumentException.class);
    artifact.put("mode", "trained");
    artifact.put("prompt_version", "old");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(RecommendationJson.stringify(artifact), "unit-test-model"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestUsesResponsesSchemaAndDoesNotStoreInputs() {
    var client = new OpenAiCandidateEvaluator("test-only-key", "unit-test-model");
    var context =
        new RecommendationInput.Context("생일", 60000, "2026-11-15T18:00:00+09:00", Map.of());
    var body = client.requestBody(context, List.of());
    assertThat(body.get("store")).isEqualTo(false);
    assertThat(RecommendationJson.stringify(body))
        .contains("json_schema", "budget_amount")
        .doesNotContain("test-only-key");
  }
}
