package com.togethermarket.recommendation.service.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.config.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.*;
import com.togethermarket.recommendation.service.job.RecommendationJobRuntime;
import com.togethermarket.reservation.repository.ReservationRepository;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RecommendationWiringTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              RecommendationPersistenceConfiguration.class,
              RecommendationExecutionConfiguration.class)
          .withBean(PurchaseRequestRepository.class, () -> mock(PurchaseRequestRepository.class))
          .withBean(PurposeCategoryRepository.class, () -> mock(PurposeCategoryRepository.class))
          .withBean(
              RecommendationCandidateRepository.class,
              () -> mock(RecommendationCandidateRepository.class))
          .withBean(
              RecommendationItemRepository.class, () -> mock(RecommendationItemRepository.class))
          .withBean(ReservationRepository.class, () -> mock(ReservationRepository.class))
          .withBean(ProductRepository.class, () -> mock(ProductRepository.class))
          .withBean(
              ProductPurposeTagRepository.class, () -> mock(ProductPurposeTagRepository.class))
          .withBean(StoreRepository.class, () -> mock(StoreRepository.class))
          .withBean(UserRepository.class, () -> mock(UserRepository.class))
          .withBean(ProductAvailabilityService.class, () -> mock(ProductAvailabilityService.class))
          .withBean(
              HybridRecommendationService.class,
              () ->
                  new HybridRecommendationService(
                      (c, candidates) -> List.of(), RankingModel.baseline()));

  @Test
  void persistenceIsOptInAndReadAdaptersDoNotStartBackgroundAi() {
    runner.run(context -> assertThat(context).doesNotHaveBean(JpaRecommendationJobStore.class));
    runner
        .withPropertyValues("recommendation.persistence.enabled=true")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(JpaRecommendationJobStore.class)
                  .hasSingleBean(JpaCatalogSource.class)
                  .doesNotHaveBean(RecommendationJobRuntime.class);
            });
  }

  @Test
  void executionNeedsApprovedQuestionLabelsRatherThanSilentDraftDefaults() {
    runner
        .withPropertyValues(
            "recommendation.persistence.enabled=true", "recommendation.execution.enabled=true")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            "recommendation.persistence.enabled=true",
            "recommendation.execution.enabled=true",
            "recommendation.questions.preference-labels.11=선호 상품 종류")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RecommendationJobRuntime.class)
                    .hasSingleBean(JpaPurchaseCreationService.class)
                    .hasSingleBean(JpaRecommendationComputation.class));
  }
}
