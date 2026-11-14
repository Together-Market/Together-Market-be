package com.togethermarket.recommendation.service.job;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class RecommendationAfterCommitTest {
  final List<Long> dispatched = new ArrayList<>();
  final RecommendationAfterCommit hook = new RecommendationAfterCommit(dispatched::add);

  void begin() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void cleanup() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void workIsDispatchedOnlyAfterCommitCallback() {
    begin();
    hook.register(1L);
    assertThat(dispatched).isEmpty();
    TransactionSynchronizationUtils.triggerBeforeCommit(false);
    assertThat(dispatched).isEmpty();
    TransactionSynchronizationUtils.triggerAfterCommit();
    assertThat(dispatched).containsExactly(1L);
  }

  @Test
  void rollbackDoesNotDispatch() {
    begin();
    hook.register(1L);
    TransactionSynchronizationUtils.triggerAfterCompletion(
        TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(dispatched).isEmpty();
  }

  @Test
  void missingRealTransactionAndReadOnlyTransactionAreRejected() {
    assertThatThrownBy(() -> hook.register(1L)).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> hook.register(1L)).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> hook.register(1L)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dispatchFailureDoesNotPretendCommittedPurchaseWasRolledBack() {
    begin();
    new RecommendationAfterCommit(
            id -> {
              throw new IllegalStateException("private database details");
            })
        .register(1L);
    assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit).doesNotThrowAnyException();
    assertThat(dispatched).isEmpty();
  }
}
