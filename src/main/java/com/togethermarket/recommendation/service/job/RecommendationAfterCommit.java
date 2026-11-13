package com.togethermarket.recommendation.service.job;

import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers inside the purchase creation transaction; rolled-back purchases are never dispatched.
 */
public final class RecommendationAfterCommit {
  private static final System.Logger LOG =
      System.getLogger(RecommendationAfterCommit.class.getName());
  private final Consumer<Long> dispatcher;

  public RecommendationAfterCommit(Consumer<Long> dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
  }

  public void register(Long requestId) {
    if (requestId == null || requestId <= 0)
      throw new IllegalArgumentException("Positive request ID required");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Register recommendation dispatch inside the purchase write transaction");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              dispatcher.accept(requestId);
            } catch (RuntimeException failure) {
              // The purchase is already committed. Do not imply POST was rolled back and invite
              // retry.
              // Durable expiry will close an unclaimed/claimed request even if notification was
              // lost.
              LOG.log(
                  System.Logger.Level.ERROR,
                  "Recommendation dispatch after commit failed; request={0}",
                  requestId);
            }
          }
        });
  }
}
