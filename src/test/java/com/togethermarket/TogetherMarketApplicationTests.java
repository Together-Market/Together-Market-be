package com.togethermarket;

import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** DB-free wiring smoke test; real persistence is checked in DatabaseSchemaIntegrationTest. */
@SpringBootTest
class TogetherMarketApplicationTests {
  @MockitoBean
  com.togethermarket.recommendation.repository.RecommendationCandidateRepository candidates;

  @MockitoBean
  com.togethermarket.recommendation.repository.PurchaseRequestRepository purchaseRequests;

  @MockitoBean
  com.togethermarket.recommendation.repository.RecommendationItemRepository recommendationItems;

  @MockitoBean com.togethermarket.reservation.repository.ReservationRepository reservations;
  @MockitoBean com.togethermarket.reservation.repository.StoreOrderRepository orders;
  @MockitoBean jakarta.persistence.EntityManager entityManager;
  @MockitoBean ProductRepository products;
  @MockitoBean StoreRepository stores;
  @MockitoBean UserRepository users;
  @MockitoBean StoreHourRepository hours;

  @MockitoBean com.togethermarket.product.repository.ProductPurposeTagRepository tags;
  @MockitoBean com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;

  @Test
  void contextLoads() {}
}
