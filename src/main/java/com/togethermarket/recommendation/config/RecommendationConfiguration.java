package com.togethermarket.recommendation.config;

import com.togethermarket.recommendation.service.CandidateEvaluator;
import com.togethermarket.recommendation.service.GeminiCandidateEvaluator;
import com.togethermarket.recommendation.service.HybridRecommendationService;
import com.togethermarket.recommendation.service.OpenAiCandidateEvaluator;
import com.togethermarket.recommendation.service.RankingModel;
import com.togethermarket.recommendation.service.RankingModelLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.ai.enabled", havingValue = "true")
public class RecommendationConfiguration {
  @Bean
  CandidateEvaluator candidateEvaluator(Environment environment) {
    if (provider(environment).equals("gemini")) {
      return new GeminiCandidateEvaluator(
          environment.getRequiredProperty("GEMINI_API_KEY"),
          model(environment),
          environment.getProperty("recommendation.ai.max-attempts", Integer.class, 2));
    }
    return new OpenAiCandidateEvaluator(
        environment.getRequiredProperty("OPENAI_API_KEY"), model(environment));
  }

  @Bean
  RankingModel rankingModel(Environment environment) throws IOException {
    String mode = environment.getProperty("recommendation.ranker.mode", "baseline");
    if (mode.equals("baseline")) {
      return RankingModel.baseline();
    }
    if (mode.equals("demo")) {
      if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"))
          || environment.acceptsProfiles(
              org.springframework.core.env.Profiles.of("prod", "production"))) {
        throw new IllegalStateException(
            "Experimental weights require the demo profile without a production profile");
      }
      try (var stream =
          new org.springframework.core.io.ClassPathResource(
                  "recommendation/demo/gemini-ranker.json")
              .getInputStream()) {
        return RankingModelLoader.loadDemo(
            new String(stream.readAllBytes(), StandardCharsets.UTF_8),
            provider(environment),
            model(environment));
      }
    }
    if (!mode.equals("trained")) {
      throw new IllegalArgumentException(
          "recommendation.ranker.mode must be baseline, trained or demo");
    }
    Path path = Path.of(environment.getRequiredProperty("recommendation.ranker.model-path"));
    return RankingModelLoader.load(
        Files.readString(path, StandardCharsets.UTF_8), provider(environment), model(environment));
  }

  private static String provider(Environment environment) {
    String value = environment.getProperty("recommendation.ai.provider", "gemini");
    if (!value.equals("gemini") && !value.equals("openai"))
      throw new IllegalArgumentException("recommendation.ai.provider must be gemini or openai");
    return value;
  }

  private static String model(Environment environment) {
    return provider(environment).equals("gemini")
        ? environment.getProperty("GEMINI_MODEL", GeminiCandidateEvaluator.DEFAULT_MODEL)
        : environment.getRequiredProperty("OPENAI_MODEL");
  }

  @Bean
  HybridRecommendationService hybridRecommendationService(
      CandidateEvaluator evaluator, RankingModel model) {
    return new HybridRecommendationService(evaluator, model);
  }
}
