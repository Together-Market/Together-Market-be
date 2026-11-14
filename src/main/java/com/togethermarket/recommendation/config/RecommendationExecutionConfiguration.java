package com.togethermarket.recommendation.config;

import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.HybridRecommendationService;
import com.togethermarket.recommendation.service.candidate.CandidateGenerator;
import com.togethermarket.recommendation.service.job.*;
import com.togethermarket.recommendation.service.persistence.*;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt in only with persistence, AI configuration and approved question labels supplied. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.execution.enabled", havingValue = "true")
public class RecommendationExecutionConfiguration {
  @Bean
  PurposeQuestionService purposeQuestionService(
      PurposeCategoryService categories, Environment env) {
    var labels =
        Binder.get(env)
            .bind(
                "recommendation.questions.preference-labels",
                Bindable.mapOf(Long.class, String.class))
            .orElseThrow(
                () ->
                    new IllegalStateException("Approved preference question labels are required"));
    return new PurposeQuestionService(categories, labels);
  }

  @Bean
  PurchaseConditionValidator purchaseConditionValidator(
      PurposeQuestionService questions, @Qualifier("recommendationClock") Clock clock) {
    return new PurchaseConditionValidator(questions, clock);
  }

  @Bean
  CandidateGenerator candidateGenerator(
      JpaCatalogSource catalog, CommonProductEligibility eligibility, Environment env) {
    return new CandidateGenerator(
        catalog,
        eligibility,
        new CandidateGenerator.Limits(
            env.getProperty("recommendation.max-products", Integer.class, 200), 12, 8, 1500));
  }

  @Bean
  JpaRecommendationInputSource recommendationInputSource(
      PurchaseRequestRepository purchases,
      PurposeCategoryRepository categories,
      ProductRepository products,
      PurchaseAnswerCodec codec,
      Environment env) {
    return new JpaRecommendationInputSource(
        purchases,
        categories,
        products,
        codec,
        env.getProperty("recommendation.max-products", Integer.class, 200));
  }

  @Bean
  JpaRecommendationComputation recommendationComputation(
      JpaRecommendationInputSource source,
      CandidateGenerator generator,
      HybridRecommendationService ranker,
      @Qualifier("recommendationClock") Clock clock) {
    return new JpaRecommendationComputation(source, generator, ranker, clock);
  }

  @Bean(destroyMethod = "close")
  RecommendationJobRuntime recommendationJobRuntime(
      JpaRecommendationJobStore store,
      JpaRecommendationComputation computation,
      @Qualifier("recommendationClock") Clock clock) {
    return new RecommendationJobRuntime(
        store,
        computation,
        clock,
        RecommendationJobRunner.Policy.defaults(),
        2,
        32,
        Duration.ofSeconds(1));
  }

  @Bean
  RecommendationAfterCommit recommendationAfterCommit(RecommendationJobRuntime runtime) {
    return new RecommendationAfterCommit(runtime::afterCommit);
  }

  @Bean
  JpaPurchaseCreationService purchaseCreationService(
      PurchaseRequestRepository purchases,
      UserRepository users,
      PurchaseConditionValidator validator,
      PurchaseAnswerCodec codec,
      RecommendationAfterCommit hook) {
    return new JpaPurchaseCreationService(purchases, users, validator, codec, hook);
  }
}
