package com.togethermarket.recommendation.config;

import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.candidate.*;
import com.togethermarket.recommendation.service.persistence.*;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.reservation.repository.ReservationRepository;
import com.togethermarket.store.repository.StoreRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.persistence.enabled", havingValue = "true")
public class RecommendationPersistenceConfiguration {
  @Bean("recommendationClock")
  Clock recommendationClock() {
    return Clock.systemUTC();
  }

  @Bean
  PurchaseAnswerCodec purchaseAnswerCodec() {
    return new PurchaseAnswerCodec();
  }

  @Bean
  JpaPurposeCategorySource purposeCategorySource(PurposeCategoryRepository repository) {
    return new JpaPurposeCategorySource(repository);
  }

  @Bean
  PurposeCategoryService purposeCategoryService(JpaPurposeCategorySource source) {
    return new PurposeCategoryService(source);
  }

  @Bean
  JpaPurchaseRequestSource purchaseRequestSource(
      PurchaseRequestRepository repository, PurchaseAnswerCodec codec) {
    return new JpaPurchaseRequestSource(repository, codec);
  }

  @Bean
  PurchaseRequestQueryService purchaseRequestQueryService(JpaPurchaseRequestSource source) {
    return new PurchaseRequestQueryService(source);
  }

  @Bean
  CommonProductEligibility productEligibility(ProductAvailabilityService service) {
    return new CommonProductEligibility(service);
  }

  @Bean
  JpaCatalogSource catalogSource(
      ProductRepository products, StoreRepository stores, ProductPurposeTagRepository tags) {
    return new JpaCatalogSource(products, stores, tags);
  }

  @Bean
  JpaRecommendationReadSource recommendationReadSource(
      PurchaseRequestRepository purchases,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      ReservationRepository reservations) {
    return new JpaRecommendationReadSource(purchases, candidates, items, reservations);
  }

  @Bean
  CandidateRevalidator candidateRevalidator(
      JpaCatalogSource catalog, CommonProductEligibility eligibility) {
    return new CandidateRevalidator(catalog, eligibility);
  }

  @Bean
  RecommendationReadService recommendationReadService(
      JpaRecommendationReadSource source,
      CandidateRevalidator revalidator,
      @Qualifier("recommendationClock") Clock clock) {
    return new RecommendationReadService(source, revalidator, clock, 100);
  }

  @Bean
  JpaRecommendationJobStore recommendationJobStore(
      PurchaseRequestRepository purchases,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      ProductRepository products,
      ProductAvailabilityService availability,
      @Qualifier("recommendationClock") Clock clock) {
    return new JpaRecommendationJobStore(
        purchases, candidates, items, clock, products, availability);
  }
}
