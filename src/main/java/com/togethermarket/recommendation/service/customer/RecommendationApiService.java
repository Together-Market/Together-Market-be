package com.togethermarket.recommendation.service.customer;

import com.togethermarket.recommendation.dto.response.RecommendationResponse;
import com.togethermarket.recommendation.service.candidate.*;
import com.togethermarket.recommendation.service.candidate.CandidateData.CheckedCandidate;
import com.togethermarket.recommendation.service.purchase.*;
import java.util.List;
import org.springframework.transaction.annotation.*;

/** One read snapshot for availability, prices and public projections; no AI calls or writes. */
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecommendationApiService {
  private final RecommendationReadService reads;
  private final RecommendationReadSource source;
  private final CustomerReadService customers;

  public RecommendationApiService(
      RecommendationReadService reads,
      RecommendationReadSource source,
      CustomerReadService customers,
      String alternativePickupPolicy) {
    if (!"PURCHASE_REQUEST".equals(alternativePickupPolicy))
      throw new IllegalArgumentException(
          "Set alternative pickup policy explicitly to PURCHASE_REQUEST");
    this.reads = reads;
    this.source = source;
    this.customers = customers;
  }

  public RecommendationResponse.Page list(String id, Long customer, Integer page, Integer size) {
    var result = reads.recommendations(id, customer, page, size);
    return new RecommendationResponse.Page(
        PurchaseRules.parseId(id, "request_id").toString(),
        "READY",
        result.message(),
        result.items().stream().map(c -> response(c, customer, false)).toList(),
        result.page(),
        result.size(),
        result.totalCount());
  }

  public RecommendationResponse detail(String id, Long customer) {
    return response(reads.detail(id, customer), customer, true);
  }

  public RecommendationResponse.Alternatives alternatives(
      String id, Long customer, Integer page, Integer size) {
    var reservation =
        source
            .findReservation(PurchaseRules.parseId(id, "reservation_id"))
            .orElseThrow(() -> failure("RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."));
    if (!reservation.customerId().equals(customer))
      throw failure("FORBIDDEN", "해당 리소스에 접근할 권한이 없습니다.");
    var purchase =
        source
            .findPurchase(reservation.purchaseRequestId())
            .orElseThrow(() -> failure("RECOMMENDATION_DATA_UNAVAILABLE", "원 구매요청을 찾을 수 없습니다."));
    var result = reads.alternatives(id, customer, purchase.pickupAt(), page, size);
    return new RecommendationResponse.Alternatives(
        reservation.id().toString(),
        result.items().stream().map(c -> response(c, customer, false)).toList(),
        result.message(),
        result.page(),
        result.size(),
        result.totalCount());
  }

  private RecommendationResponse response(CheckedCandidate checked, Long customer, boolean detail) {
    // Missing catalog rows cannot satisfy the non-null product/store response contract.
    if (checked.items().size() != checked.saved().items().size())
      throw failure("RECOMMENDATION_DATA_UNAVAILABLE", "추천 구성 상품을 확인할 수 없습니다.");
    var items =
        checked.items().stream()
            .map(
                i -> {
                  var product =
                      customers.productForRecommendation(i.product().id().toString(), customer);
                  return new RecommendationResponse.Item(
                      i.saved().id().toString(),
                      RecommendationResponse.Product.from(product),
                      product.store(),
                      i.saved().quantity(),
                      i.saved().unitPriceSnapshot(),
                      i.product().priceAmount());
                })
            .toList();
    var hours =
        detail
            ? items.stream()
                .map(
                    i ->
                        new RecommendationResponse.Hours(
                            i.store().id(), customers.store(i.store().id(), customer).hours()))
                .toList()
            : null;
    return new RecommendationResponse(
        checked.saved().id().toString(),
        checked.saved().purchaseRequestId().toString(),
        checked.saved().rank(),
        checked.saved().reason(),
        checked.totalAmount(),
        checked.available() ? "AVAILABLE" : "UNAVAILABLE",
        checked.reasons().stream()
            .map(
                r ->
                    new RecommendationResponse.Reason(
                        r.productId() == null ? null : r.productId().toString(), r.code()))
            .toList(),
        items,
        hours);
  }

  private static PurchaseInputException failure(String code, String text) {
    return new PurchaseInputException(code, text, List.of());
  }
}
