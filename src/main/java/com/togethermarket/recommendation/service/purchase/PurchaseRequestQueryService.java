package com.togethermarket.recommendation.service.purchase;

import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse;
import java.util.List;
import java.util.Objects;

public final class PurchaseRequestQueryService {
  private final PurchaseRequestSource source;

  public PurchaseRequestQueryService(PurchaseRequestSource source) {
    this.source = Objects.requireNonNull(source);
  }

  /**
   * sessionCustomerId is supplied by BE1's authenticated CUSTOMER principal, never request body.
   */
  public PurchaseRequestResponse get(String requestId, Long sessionCustomerId) {
    if (sessionCustomerId == null || sessionCustomerId <= 0) {
      throw new PurchaseInputException("UNAUTHORIZED", "로그인이 필요합니다.", List.of());
    }
    Long id = PurchaseRules.parseId(requestId, "request_id");
    var snapshot =
        source
            .findById(id)
            .orElseThrow(
                () ->
                    new PurchaseInputException(
                        "PURCHASE_REQUEST_NOT_FOUND", "구매요청을 찾을 수 없습니다.", List.of()));
    if (!id.equals(snapshot.id())) {
      throw new IllegalStateException("Purchase request source returned a different ID");
    }
    if (!sessionCustomerId.equals(snapshot.customerId())) {
      throw new PurchaseInputException("FORBIDDEN", "해당 리소스에 접근할 권한이 없습니다.", List.of());
    }
    return new PurchaseRequestResponse(
        snapshot.id(),
        snapshot.customerId(),
        snapshot.purposeCategoryId(),
        snapshot.budgetAmount(),
        snapshot.preferredPickupAt(),
        snapshot.answers(),
        snapshot.status(),
        snapshot.createdAt(),
        snapshot.failureCode());
  }
}
