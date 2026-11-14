package com.togethermarket.recommendation.config;

import com.togethermarket.recommendation.service.candidate.*;
import com.togethermarket.recommendation.service.customer.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.api.enabled", havingValue = "true")
public class RecommendationApiConfiguration {
  @Bean
  RecommendationApiService recommendationApiService(
      RecommendationReadService reads,
      RecommendationReadSource source,
      CustomerReadService customers,
      Environment env) {
    return new RecommendationApiService(
        reads,
        source,
        customers,
        env.getRequiredProperty("recommendation.alternatives.pickup-policy"));
  }
}
