package com.togethermarket.recommendation.service.purchase;

import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse.Answer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only adapter boundary; not a second JPA repository or Entity. */
@FunctionalInterface
public interface PurchaseRequestSource {
  Optional<Snapshot> findById(Long requestId);

  record Snapshot(
      Long id,
      Long customerId,
      Long purposeCategoryId,
      long budgetAmount,
      OffsetDateTime preferredPickupAt,
      List<Answer> answers,
      String status,
      OffsetDateTime createdAt,
      String failureCode) {
    public Snapshot {
      answers = List.copyOf(answers);
      if (id == null
          || id <= 0
          || customerId == null
          || customerId <= 0
          || purposeCategoryId == null
          || purposeCategoryId <= 0
          || budgetAmount < PurchaseRules.MIN_BUDGET
          || budgetAmount > PurchaseRules.MAX_BUDGET
          || preferredPickupAt == null
          || createdAt == null
          || status == null
          || !List.of("RECOMMENDING", "READY", "FAILED").contains(status)) {
        throw new IllegalArgumentException("Invalid purchase request snapshot");
      }
      if (status.equals("FAILED")) {
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
          throw new IllegalArgumentException(
              "FAILED requires a public failure code, not a raw error");
        }
      } else if (failureCode != null) {
        throw new IllegalArgumentException("Only FAILED may have a failure code");
      }
    }
  }
}
