package com.togethermarket.recommendation.service.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.*;
import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse.Answer;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.candidate.*;
import com.togethermarket.reservation.repository.ReservationRepository;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PersistenceAdaptersTest {
  static <T> T identified(T entity, long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    return entity;
  }

  @Test
  void timeMappingUsesKstAcrossUtcDateBoundaryAndSecondsPrecision() {
    var instant = Instant.parse("2026-11-13T18:00:00.999Z");
    var stored = KoreanDateTime.toDatabase(instant);
    assertThat(stored).isEqualTo(LocalDateTime.parse("2026-11-14T03:00:00"));
    assertThat(KoreanDateTime.toResponse(stored).toString()).isEqualTo("2026-11-14T03:00+09:00");
    assertThat(KoreanDateTime.toInstant(stored)).isEqualTo(Instant.parse("2026-11-13T18:00:00Z"));
  }

  @Test
  void answersRoundTripAsArrayWithLabelsAndOptionalEmptyText() {
    var codec = new PurchaseAnswerCodec();
    var original =
        List.of(new Answer("style", "따뜻함", "분위기"), new Answer("excluded_products", "", "제외 상품"));
    var json = codec.encode(original);
    assertThat(json).startsWith("[").contains("question_code");
    assertThat(codec.decode(json)).isEqualTo(original);
    assertThatThrownBy(() -> codec.decode("\"[]\"")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> codec.decode("[{\"question_code\":\"style\",\"value\":\"x\"}]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eligibilityConvertsOffsetTimeAndPreservesCommonReasonCodes() {
    var common = mock(ProductAvailabilityService.class);
    var pickup = OffsetDateTime.parse("2026-11-14T15:00:00+09:00");
    var now = Instant.parse("2026-11-13T09:00:00Z");
    when(common.check(1L, pickup.toInstant(), now))
        .thenReturn(new AvailabilityResult(List.of(AvailabilityReason.PRODUCT_NOT_ON_SALE)));
    var result = new CommonProductEligibility(common).check(1L, pickup, now);
    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCodes()).containsExactly("PRODUCT_NOT_ON_SALE");
    verify(common).check(1L, pickup.toInstant(), now);
  }

  @Test
  void catalogBatchesStoresAndTagsAndSupportsZeroWon() {
    var products = mock(ProductRepository.class);
    var stores = mock(StoreRepository.class);
    var tags = mock(ProductPurposeTagRepository.class);
    var p1 =
        identified(new Product(10L, "꽃", "설명", null, BigDecimal.ZERO, 0, SaleStatus.ON_SALE), 1);
    var p2 =
        identified(
            new Product(20L, "디저트", "설명", null, BigDecimal.valueOf(10000), 0, SaleStatus.ON_SALE),
            2);
    when(products.findAllById(Set.of(1L, 2L))).thenReturn(List.of(p1, p2));
    when(stores.findAllById(Set.of(10L, 20L)))
        .thenReturn(
            List.of(
                identified(new Store(7L, "꽃집", "꽃", "주소", null, null, "010"), 10),
                identified(new Store(8L, "제과", "디저트", "주소", null, null, "010"), 20)));
    when(tags.findByIdProductIdIn(Set.of(1L, 2L)))
        .thenReturn(List.of(new ProductPurposeTag(1L, 11L)));
    var source = new JpaCatalogSource(products, stores, tags);
    var result = source.findProducts(Set.of(1L, 2L));
    assertThat(result.get(1L).priceAmount()).isZero();
    assertThat(result.get(1L).purposeCategoryIds()).containsExactly(11L);
    assertThat(result.get(2L).purposeCategoryIds()).isEmpty();
    verify(stores, times(1)).findAllById(any());
    var generator =
        new CandidateGenerator(
            ids -> result,
            (id, pickup, now) -> ProductEligibility.Result.available(),
            new CandidateGenerator.Limits(2, 1, 1, 1500));
    var candidates =
        generator.generate(
            new CandidateData.Purchase(
                1L, 7L, 10000, OffsetDateTime.parse("2026-11-14T15:00:00+09:00"), "READY"),
            Set.of(1L, 2L),
            new CandidateGenerator.Preferences(11L, Set.of(), Map.of()),
            Instant.parse("2026-11-13T09:00:00Z"));
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().totalAmount()).isEqualTo(10000);
  }

  @Test
  void categoryAdapterUsesOnlyExistingSharedFields() {
    var repository = mock(PurposeCategoryRepository.class);
    when(repository.findAll())
        .thenReturn(
            List.of(
                identified(new PurposeCategory(null, "기념일"), 1),
                identified(new PurposeCategory(1L, "생일"), 11)));
    var rows = new JpaPurposeCategorySource(repository).findAll();
    assertThat(rows).allMatch(r -> r.active() && r.displayOrder() == 0);
    assertThat(rows.getLast().parentId()).isEqualTo(1L);
  }

  @Test
  void recommendationItemsAreBatchLoadedWithSavedPricesAndQuantities() {
    var purchases = mock(PurchaseRequestRepository.class);
    var candidates = mock(RecommendationCandidateRepository.class);
    var items = mock(RecommendationItemRepository.class);
    var reservations = mock(ReservationRepository.class);
    when(candidates.findByPurchaseRequestIdOrderByRankAscIdAsc(1L))
        .thenReturn(List.of(identified(new RecommendationCandidate(1L, 1, "이유"), 2)));
    when(items.findByRecommendationCandidateIdInOrderByIdAsc(List.of(2L)))
        .thenReturn(
            List.of(
                identified(new RecommendationItem(2L, 10L, 1, BigDecimal.ZERO), 3),
                identified(new RecommendationItem(2L, 20L, 2, BigDecimal.valueOf(5000)), 4)));
    var result =
        new JpaRecommendationReadSource(purchases, candidates, items, reservations)
            .findCandidates(1L);
    assertThat(result.getFirst().items().getFirst().unitPriceSnapshot()).isZero();
    assertThat(result.getFirst().items().getLast().quantity()).isEqualTo(2);
    verify(items, times(1)).findByRecommendationCandidateIdInOrderByIdAsc(any());
  }

  @Test
  void purchaseReadMapsExactMoneyAndKstAndDecodesAnswers() {
    var repo = mock(PurchaseRequestRepository.class);
    var row =
        identified(
            new PurchaseRequest(
                7L,
                11L,
                BigDecimal.valueOf(10000),
                LocalDateTime.of(2026, 11, 14, 15, 0),
                RecommendationStatus.RECOMMENDING,
                null,
                LocalDateTime.of(2026, 11, 13, 18, 0),
                "[]"),
            1);
    when(repo.findById(1L)).thenReturn(Optional.of(row));
    var snapshot =
        new JpaPurchaseRequestSource(repo, new PurchaseAnswerCodec()).findById(1L).orElseThrow();
    assertThat(snapshot.preferredPickupAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(snapshot.budgetAmount()).isEqualTo(10000);
    assertThat(snapshot.answers()).isEmpty();
  }
}
