package com.togethermarket.recommendation.service.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.repository.*;
import com.togethermarket.recommendation.config.CustomerReadConfiguration;
import com.togethermarket.recommendation.controller.CustomerReadController;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.store.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CustomerReadWiringTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(CustomerReadConfiguration.class, CustomerReadController.class)
          .withBean(ProductRepository.class, () -> mock(ProductRepository.class))
          .withBean(
              ProductPurposeTagRepository.class, () -> mock(ProductPurposeTagRepository.class))
          .withBean(StoreRepository.class, () -> mock(StoreRepository.class))
          .withBean(StoreHourRepository.class, () -> mock(StoreHourRepository.class))
          .withBean(
              RecommendationCandidateRepository.class,
              () -> mock(RecommendationCandidateRepository.class))
          .withBean(
              RecommendationItemRepository.class, () -> mock(RecommendationItemRepository.class))
          .withBean(PurchaseRequestRepository.class, () -> mock(PurchaseRequestRepository.class));

  ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
        "recommendation.customer-read.enabled=true",
        "recommendation.customer-read.visible-sale-statuses=ON_SALE,SOLD_OUT,PAUSED",
        "recommendation.customer-read.coordinate-policy=DETAIL_NULL_MAP_NULL");
  }

  @Test
  void disabledByDefaultAndRequiresExplicitPoliciesWhenEnabled() {
    runner.run(
        c ->
            assertThat(c)
                .hasNotFailed()
                .doesNotHaveBean(CustomerReadService.class)
                .doesNotHaveBean(CustomerReadController.class));
    runner
        .withPropertyValues("recommendation.customer-read.enabled=true")
        .run(c -> assertThat(c).hasFailed());
    enabled()
        .withPropertyValues("recommendation.customer-read.visible-sale-statuses=STOPPED")
        .run(c -> assertThat(c).hasFailed());
    enabled()
        .withPropertyValues("recommendation.customer-read.coordinate-policy=UNKNOWN")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void sessionAuthenticationFailsClosedWhenNotLoggedIn() {
    enabled()
        .run(
            c -> {
              assertThat(c).hasNotFailed().hasSingleBean(CustomerReadController.class);
              assertThatThrownBy(() -> c.getBean(CustomerReadAccess.class).requireCustomer(null))
                  .isInstanceOfSatisfying(
                      PurchaseInputException.class,
                      e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
            });
  }

  @Test
  void previousCoordinatePolicySpellingRemainsAccepted() {
    enabled()
        .withPropertyValues("recommendation.customer-read.coordinate-policy=DETAIL_NULL_MAP_ERROR")
        .run(c -> assertThat(c).hasNotFailed().hasSingleBean(CustomerReadService.class));
  }

  @Test
  void explicitAuthenticationAdapterReplacesFallback() {
    enabled()
        .withBean(CustomerReadAccess.class, () -> request -> 2L)
        .run(
            c -> {
              assertThat(c).hasNotFailed().hasSingleBean(CustomerReadAccess.class);
              assertThat(c.getBean(CustomerReadAccess.class).requireCustomer(null)).isEqualTo(2L);
            });
  }
}
