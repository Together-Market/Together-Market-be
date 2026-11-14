package com.togethermarket.recommendation.service;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.config.RecommendationConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoRankingModelTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(RecommendationConfiguration.class)
          .withPropertyValues(
              "recommendation.ai.enabled=true",
              "recommendation.ranker.mode=demo",
              "GEMINI_API_KEY=test");

  @Test
  void originalModelLoadsOnlyInDemoProfileAndRetainsProvenance() throws Exception {
    var original =
        new String(
            getClass()
                .getResourceAsStream("/recommendation/demo/gemini-ranker.json")
                .readAllBytes(),
            StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                RankingModelLoader.load(original, "gemini", GeminiCandidateEvaluator.DEFAULT_MODEL))
        .isInstanceOf(IllegalArgumentException.class);
    runner.run(c -> assertThat(c).hasFailed());
    runner
        .withPropertyValues("spring.profiles.active=demo")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              var model = c.getBean(RankingModel.class);
              assertThat(model.mode()).isEqualTo("demo");
              assertThat(model.weights().getFirst()).isEqualTo(2.8080075005669327);
              assertThat(model.version()).startsWith("gemini-ai-demo-");
            });
    runner
        .withPropertyValues("spring.profiles.active=demo,prod")
        .run(c -> assertThat(c).hasFailed());
    runner
        .withPropertyValues("spring.profiles.active=demo", "GEMINI_MODEL=gemini-other")
        .run(c -> assertThat(c).hasFailed());
    assertThatThrownBy(
            () ->
                RankingModelLoader.loadDemo(
                    original.replace("\"deployable\": false", "\"deployable\": true"),
                    "gemini",
                    GeminiCandidateEvaluator.DEFAULT_MODEL))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
