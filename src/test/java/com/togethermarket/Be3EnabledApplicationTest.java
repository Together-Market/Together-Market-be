package com.togethermarket;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.recommendation.service.HybridRecommendationService;
import com.togethermarket.recommendation.service.job.RecommendationJobRuntime;
import com.togethermarket.recommendation.service.persistence.JpaPurchaseCreationService;
import com.togethermarket.reservation.service.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "recommendation.persistence.enabled=true",
      "recommendation.execution.enabled=true",
      "recommendation.questions.preference-labels.11=선호 상품 종류"
    })
class Be3EnabledApplicationTest {
  @MockitoBean com.togethermarket.user.repository.UserRepository users;
  @MockitoBean com.togethermarket.product.repository.ProductRepository products;
  @MockitoBean com.togethermarket.product.repository.ProductPurposeTagRepository tags;
  @MockitoBean com.togethermarket.store.repository.StoreRepository stores;
  @MockitoBean com.togethermarket.store.repository.StoreHourRepository hours;
  @MockitoBean com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;
  @MockitoBean com.togethermarket.recommendation.repository.PurchaseRequestRepository purchases;

  @MockitoBean
  com.togethermarket.recommendation.repository.RecommendationCandidateRepository candidates;

  @MockitoBean com.togethermarket.recommendation.repository.RecommendationItemRepository items;
  @MockitoBean com.togethermarket.reservation.repository.ReservationRepository reservations;
  @MockitoBean com.togethermarket.reservation.repository.StoreOrderRepository orders;
  @MockitoBean jakarta.persistence.EntityManager entityManager;
  @MockitoBean org.springframework.transaction.PlatformTransactionManager transactions;
  // Keep real BE3 wiring and runtime, substituting only the external AI boundary.
  @MockitoBean HybridRecommendationService ranker;
  @Autowired ApplicationContext context;

  @Test
  void realBe3ExecutionAndReservationServicesStartTogether() {
    assertThat(context.getBeansOfType(Clock.class))
        .containsOnlyKeys("reservationClock", "recommendationClock");
    assertThat(context.getBean(JpaPurchaseCreationService.class)).isNotNull();
    assertThat(context.getBean(RecommendationJobRuntime.class)).isNotNull();
    assertThat(context.getBean(ReservationService.class)).isNotNull();
    assertThat(context.getBean(OwnerOrderService.class)).isNotNull();
    assertThat(context.getBean(ReservationExpiryWorker.class)).isNotNull();
  }
}
