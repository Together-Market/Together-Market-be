package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.config.RecommendationConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeminiModelConfigurationTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(RecommendationConfiguration.class);

  @Test
  void defaultsToGeminiOnlyWhenExplicitlyEnabledAndRequiresItsKey() {
    runner.run(c -> assertThat(c).doesNotHaveBean(CandidateEvaluator.class));
    runner
        .withPropertyValues("recommendation.ai.enabled=true", "GEMINI_API_KEY=test-key")
        .run(c -> assertThat(c).hasNotFailed().hasSingleBean(GeminiCandidateEvaluator.class));
    runner.withPropertyValues("recommendation.ai.enabled=true").run(c -> assertThat(c).hasFailed());
    runner
        .withPropertyValues("recommendation.ai.enabled=true", "recommendation.ai.provider=invalid")
        .run(c -> assertThat(c).hasFailed());
    runner
        .withPropertyValues(
            "recommendation.ai.enabled=true",
            "recommendation.ai.provider=openai",
            "OPENAI_API_KEY=test-key",
            "OPENAI_MODEL=test-model")
        .run(c -> assertThat(c).hasNotFailed().hasSingleBean(OpenAiCandidateEvaluator.class));
  }

  @Test
  void refusesWeightsFromOtherProviderModelOrSyntheticExperiment() {
    var model = new HashMap<String, Object>();
    model.put("artifact_version", 1);
    model.put("mode", "trained");
    model.put("api_provider", "gemini");
    model.put("api_model", "gemini-test");
    model.put("ranker_version", "test-fixture");
    model.put("feature_version", RankingModel.FEATURE_VERSION);
    model.put("feature_names", RankingModel.FEATURE_NAMES);
    model.put("prompt_version", RecommendationJson.promptVersion());
    model.put("training_scenarios", 2);
    model.put("training_comparisons", 6);
    model.put("weights", List.of(1, 1, 1, 1, 1, 1));
    assertThat(
            RankingModelLoader.load(RecommendationJson.stringify(model), "gemini", "gemini-test")
                .mode())
        .isEqualTo("trained");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(
                    RecommendationJson.stringify(model), "openai", "gemini-test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(
                    RecommendationJson.stringify(model), "gemini", "gemini-other"))
        .isInstanceOf(IllegalArgumentException.class);
    model.remove("api_provider");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(
                    RecommendationJson.stringify(model), "gemini", "gemini-test"))
        .isInstanceOf(IllegalArgumentException.class);
    model.put("api_provider", "gemini");
    model.put("mode", "ai_synthetic_experiment");
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(
                    RecommendationJson.stringify(model), "gemini", "gemini-test"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
