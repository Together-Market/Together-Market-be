package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.candidate.CandidateData.CheckedCandidate;
import com.togethermarket.recommendation.service.candidate.CandidateData.Purchase;
import com.togethermarket.recommendation.service.candidate.CandidateData.SavedCandidate;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.recommendation.service.purchase.PurchaseRules;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** REC-01/02 and RES-04 core. Public DTO mapping and HTTP authentication are separate adapters. */
public final class RecommendationReadService {
  private static final Comparator<SavedCandidate> ORDER =
      Comparator.comparingInt(SavedCandidate::rank).thenComparing(SavedCandidate::id);
  private final RecommendationReadSource source;
  private final CandidateRevalidator revalidator;
  private final Clock clock;
  private final int maxPageSize;

  public RecommendationReadService(
      RecommendationReadSource source,
      CandidateRevalidator revalidator,
      Clock clock,
      int maxPageSize) {
    this.source = Objects.requireNonNull(source);
    this.revalidator = Objects.requireNonNull(revalidator);
    this.clock = Objects.requireNonNull(clock);
    if (maxPageSize < 20)
      throw new IllegalArgumentException("Page limit must allow default size 20");
    this.maxPageSize = maxPageSize;
  }

  /** Private service result, not the public API envelope. */
  public record Page(
      List<CheckedCandidate> items,
      int page,
      int size,
      long totalCount,
      long availableCount,
      String message) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public Page recommendations(
      String requestId, Long sessionCustomerId, Integer page, Integer size) {
    requireSession(sessionCustomerId);
    int[] pagination = pagination(page, size);
    Purchase purchase =
        ownedPurchase(PurchaseRules.parseId(requestId, "request_id"), sessionCustomerId);
    requireReady(purchase);
    List<SavedCandidate> saved = orderedCandidates(purchase.id());
    var checked = revalidator.check(purchase, saved, clock.instant());
    long available = checked.stream().filter(CheckedCandidate::available).count();
    String message =
        checked.isEmpty()
            ? "현재 조건에 맞는 추천 조합이 없습니다. 구매 조건을 변경해 주세요."
            : available == 0 ? "현재 선택 가능한 추천 조합이 없습니다. 구매 조건을 변경해 주세요." : null;
    // Ordinary recommendations retain unavailable candidates. Count is before pagination.
    return slice(checked, pagination, available, message);
  }

  public CheckedCandidate detail(String candidateId, Long sessionCustomerId) {
    requireSession(sessionCustomerId);
    Long id = PurchaseRules.parseId(candidateId, "candidate_id");
    SavedCandidate candidate =
        source
            .findCandidate(id)
            .orElseThrow(() -> failure("RECOMMENDATION_NOT_FOUND", "추천 후보를 찾을 수 없습니다."));
    if (!id.equals(candidate.id())) throw new IllegalStateException("Candidate source ID mismatch");
    Purchase purchase = ownedPurchase(candidate.purchaseRequestId(), sessionCustomerId);
    requireReady(purchase);
    return revalidator.check(purchase, List.of(candidate), clock.instant()).getFirst();
  }

  /**
   * The caller must explicitly supply the team-agreed alternative pickup time. We do not silently
   * choose between original purchase time and individual reservation pickup times.
   */
  public Page alternatives(
      String reservationId,
      Long sessionCustomerId,
      OffsetDateTime validationPickupAt,
      Integer page,
      Integer size) {
    requireSession(sessionCustomerId);
    int[] pagination = pagination(page, size);
    Long id = PurchaseRules.parseId(reservationId, "reservation_id");
    var reservation =
        source
            .findReservation(id)
            .orElseThrow(() -> failure("RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."));
    if (!id.equals(reservation.id()))
      throw new IllegalStateException("Reservation source ID mismatch");
    requireOwner(sessionCustomerId, reservation.customerId());
    if (!reservation.failed())
      throw failure("RESERVATION_NOT_FAILED", "실패한 예약만 대체 후보를 조회할 수 있습니다.");
    Purchase purchase = ownedPurchase(reservation.purchaseRequestId(), sessionCustomerId);
    requireReady(purchase);
    SavedCandidate original =
        source
            .findCandidate(reservation.selectedCandidateId())
            .orElseThrow(() -> failure("RECOMMENDATION_DATA_UNAVAILABLE", "원 추천 정보를 확인할 수 없습니다."));
    if (!original.id().equals(reservation.selectedCandidateId())
        || !original.purchaseRequestId().equals(purchase.id())) {
      throw new IllegalStateException("Reservation and original candidate link mismatch");
    }
    Objects.requireNonNull(validationPickupAt, "An explicit alternative pickup policy is required");
    var scope =
        new Purchase(
            purchase.id(),
            purchase.customerId(),
            purchase.budgetAmount(),
            validationPickupAt,
            purchase.status());
    var seenPairs = new HashSet<List<Long>>();
    seenPairs.add(original.productPair());
    List<SavedCandidate> alternatives =
        orderedCandidates(purchase.id()).stream()
            .filter(c -> !c.id().equals(original.id()))
            .filter(c -> seenPairs.add(c.productPair()))
            .toList();
    // A rejected store can still supply a different product. Never exclude it wholesale.
    var valid =
        revalidator.check(scope, alternatives, clock.instant()).stream()
            .filter(CheckedCandidate::available)
            .toList();
    String message = valid.isEmpty() ? "현재 예약 가능한 다른 조합이 없습니다. 구매 조건을 변경해 주세요." : null;
    return slice(valid, pagination, valid.size(), message);
  }

  private Purchase ownedPurchase(Long id, Long customerId) {
    var purchase =
        source
            .findPurchase(id)
            .orElseThrow(() -> failure("PURCHASE_REQUEST_NOT_FOUND", "구매요청을 찾을 수 없습니다."));
    if (!id.equals(purchase.id())) throw new IllegalStateException("Purchase source ID mismatch");
    requireOwner(customerId, purchase.customerId());
    return purchase;
  }

  private List<SavedCandidate> orderedCandidates(Long purchaseId) {
    var ids = new HashSet<Long>();
    var rows = source.findCandidates(purchaseId);
    for (var row : rows) {
      if (!row.purchaseRequestId().equals(purchaseId) || !ids.add(row.id())) {
        throw new IllegalStateException("Candidate source returned unrelated/duplicate rows");
      }
    }
    return rows.stream().sorted(ORDER).toList();
  }

  private int[] pagination(Integer page, Integer size) {
    int p = page == null ? 1 : page;
    int s = size == null ? 20 : size;
    if (p < 1) throw PurchaseInputException.invalid("page", "페이지는 1 이상이어야 합니다.");
    if (s < 1 || s > maxPageSize)
      throw PurchaseInputException.invalid("size", "지원하는 페이지 크기를 벗어났습니다.");
    return new int[] {p, s};
  }

  private static Page slice(
      List<CheckedCandidate> rows, int[] pagination, long available, String message) {
    int page = pagination[0];
    int size = pagination[1];
    long offset = ((long) page - 1) * size;
    var items =
        offset >= rows.size()
            ? List.<CheckedCandidate>of()
            : rows.subList((int) offset, (int) Math.min(offset + size, rows.size()));
    return new Page(items, page, size, rows.size(), available, message);
  }

  private static void requireSession(Long customerId) {
    if (customerId == null || customerId <= 0) throw failure("UNAUTHORIZED", "로그인이 필요합니다.");
  }

  private static void requireOwner(Long expected, Long actual) {
    if (!expected.equals(actual)) throw failure("FORBIDDEN", "해당 리소스에 접근할 권한이 없습니다.");
  }

  private static void requireReady(Purchase purchase) {
    if (purchase.status().equals("RECOMMENDING"))
      throw failure("RECOMMENDATION_IN_PROGRESS", "추천 처리 중입니다.");
    if (purchase.status().equals("FAILED"))
      throw failure("RECOMMENDATION_FAILED", "추천 처리에 실패했습니다.");
  }

  private static PurchaseInputException failure(String code, String message) {
    return new PurchaseInputException(code, message, List.of());
  }
}
