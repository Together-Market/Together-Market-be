package com.togethermarket.recommendation.config;

import com.togethermarket.recommendation.service.CandidateEvaluator;
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
    return new OpenAiCandidateEvaluator(
        environment.getRequiredProperty("OPENAI_API_KEY"),
        environment.getRequiredProperty("OPENAI_MODEL"));
  }

  @Bean
  RankingModel rankingModel(Environment environment) throws IOException {
    String mode = environment.getProperty("recommendation.ranker.mode", "baseline");
    if (mode.equals("baseline")) {
      return RankingModel.baseline();
    }
    if (!mode.equals("trained")) {
      throw new IllegalArgumentException("recommendation.ranker.mode must be baseline or trained");
    }
    Path path = Path.of(environment.getRequiredProperty("recommendation.ranker.model-path"));
    return RankingModelLoader.load(
        Files.readString(path, StandardCharsets.UTF_8),
        environment.getRequiredProperty("OPENAI_MODEL"));
  }

  @Bean
  HybridRecommendationService hybridRecommendationService(
      CandidateEvaluator evaluator, RankingModel model) {
    return new HybridRecommendationService(evaluator, model);
  }
}
