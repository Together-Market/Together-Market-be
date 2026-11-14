package com.togethermarket.recommendation.service.persistence;

import static com.togethermarket.recommendation.service.persistence.PersistenceAdaptersTest.identified;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.entity.Product;
import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.product.service.AvailabilityResult;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import com.togethermarket.recommendation.service.RecommendationInput;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreDistancePersistenceTest {
  @ParameterizedTest
  @ValueSource(doubles = {499.99, 500.01, 700})
  void rechecksCurrentCoordinatesInsteadOfTrustingAiSnapshot(double currentMeters) {
    var purchases = mock(PurchaseRequestRepository.class);
    var candidates = mock(RecommendationCandidateRepository.class);
    var items = mock(RecommendationItemRepository.class);
    var products = mock(ProductRepository.class);
    var stores = mock(StoreRepository.class);
    var availability = mock(ProductAvailabilityService.class);
    var now = Instant.parse("2026-11-13T09:00:00Z");
    var row =
        identified(
            new PurchaseRequest(
                7L,
                11L,
                BigDecimal.valueOf(10000),
                LocalDateTime.of(2026, 11, 14, 15, 0),
                RecommendationStatus.RECOMMENDING,
                null,
                KoreanDateTime.toDatabase(now),
                "[]"),
            1);
    when(purchases.findLockedById(1L)).thenReturn(Optional.of(row));
    when(products.findAllById(any()))
        .thenReturn(
            List.of(
                identified(
                    new Product(
                        10L, "음료", "", null, BigDecimal.valueOf(1000), 0, SaleStatus.ON_SALE),
                    1),
                identified(
                    new Product(
                        20L, "과자", "", null, BigDecimal.valueOf(1000), 0, SaleStatus.ON_SALE),
                    2)));
    when(stores.findAllById(any()))
        .thenReturn(List.of(store(10, -250), store(20, currentMeters - 250)));
    when(availability.check(anyLong(), any(), any())).thenReturn(new AvailabilityResult(List.of()));
    when(candidates.save(any())).thenAnswer(call -> identified(call.getArgument(0), 1));
    var store =
        new JpaRecommendationJobStore(
            purchases,
            candidates,
            items,
            Clock.fixed(now, ZoneOffset.UTC),
            products,
            availability,
            stores);
    var lease = store.claim(1L, UUID.randomUUID(), now, Duration.ofSeconds(40)).orElseThrow();
    var stale =
        new RecommendationInput.Candidate(
            "pair",
            List.of(
                new RecommendationInput.Product("1", "10", "음료", "", "카페", 1000, true),
                new RecommendationInput.Product("2", "20", "과자", "", "제과", 1000, true)),
            10.0,
            true);
    assertThat(
            store.complete(
                lease, List.of(new RankedCandidate(stale, "추천 이유", 90, "baseline", "v1")), now))
        .isTrue();
    assertThat(row.getStatus()).isEqualTo(RecommendationStatus.READY);
    verify(candidates, times(currentMeters <= 500 ? 1 : 0)).save(any());
    verify(items, times(currentMeters <= 500 ? 2 : 0)).save(any());
  }

  private static Store store(long id, double northMeters) {
    double latitude = 37.3211736204431 + Math.toDegrees(northMeters / 6371000);
    return identified(
        new Store(
            7L,
            "점포 " + id,
            "카페",
            "주소 " + id,
            BigDecimal.valueOf(latitude),
            new BigDecimal("127.110115610261"),
            "010"),
        id);
  }
}
