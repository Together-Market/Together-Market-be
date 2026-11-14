package com.togethermarket.recommendation.service.persistence;

import static com.togethermarket.recommendation.service.persistence.PersistenceAdaptersTest.identified;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.repository.PurchaseRequestRepository;
import com.togethermarket.recommendation.service.job.RecommendationAfterCommit;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class JpaPurchaseCreationTest {
  final PurchaseRequestRepository purchases = mock(PurchaseRequestRepository.class);
  final UserRepository users = mock(UserRepository.class);
  final List<Long> dispatched = new ArrayList<>();
  final PurchaseConditionValidator validator =
      new PurchaseConditionValidator(
          new PurposeQuestionService(
              new PurposeCategoryService(
                  () ->
                      List.of(
                          new PurposeCategorySource.Category(1L, null, "기념일", 0, true),
                          new PurposeCategorySource.Category(11L, 1L, "생일", 0, true))),
              Map.of(11L, "선호 종류")),
          Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC));
  final JpaPurchaseCreationService service =
      new JpaPurchaseCreationService(
          purchases,
          users,
          validator,
          new PurchaseAnswerCodec(),
          new RecommendationAfterCommit(dispatched::add));

  CreatePurchaseRequest input() {
    return new CreatePurchaseRequest(
        "11",
        10000L,
        "2026-11-14T06:00:00Z",
        List.of(
            new CreatePurchaseRequest.Answer("style", "따뜻함"),
            new CreatePurchaseRequest.Answer("recipient", "친구"),
            new CreatePurchaseRequest.Answer("category_preference", "꽃")));
  }

  @AfterEach
  void cleanup() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void savesValidatedSnapshotsInKstAndDispatchesOnlyAfterCommit() {
    when(users.findById(7L))
        .thenReturn(Optional.of(new User("test@example.invalid", "hash", "고객", UserRole.CUSTOMER)));
    when(purchases.saveAndFlush(any())).thenAnswer(call -> identified(call.getArgument(0), 100));
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var response = service.create(input(), 7L);
    assertThat(response.preferredPickupAt())
        .isEqualTo(OffsetDateTime.parse("2026-11-14T15:00:00+09:00"));
    assertThat(response.createdAt()).isEqualTo(OffsetDateTime.parse("2026-11-13T18:00:00+09:00"));
    assertThat(response.status()).isEqualTo("RECOMMENDING");
    assertThat(response.customerId()).isEqualTo(7L);
    assertThat(response.answers().getLast().label()).isEqualTo("선호 종류");
    assertThat(dispatched).isEmpty();
    TransactionSynchronizationUtils.triggerAfterCommit();
    assertThat(dispatched).containsExactly(100L);
  }

  @Test
  void unauthenticatedOrOwnerIdentityCannotCreatePurchase() {
    assertThatThrownBy(() -> service.create(input(), null))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    when(users.findById(7L))
        .thenReturn(Optional.of(new User("test@example.invalid", "hash", "점주", UserRole.OWNER)));
    assertThatThrownBy(() -> service.create(input(), 7L))
        .isInstanceOfSatisfying(
            PurchaseInputException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    verifyNoInteractions(purchases);
    assertThat(dispatched).isEmpty();
  }
}
