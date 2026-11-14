package com.togethermarket.reservation.service;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.reservation.dto.response.OwnerOrderResponse;
import com.togethermarket.reservation.entity.*;
import com.togethermarket.reservation.repository.*;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OwnerOrderService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final ReservationRepository reservations;
  private final StoreOrderRepository orders;
  private final StoreRepository stores;
  private final UserRepository users;
  private final Clock clock;

  public OwnerOrderService(
      ReservationRepository reservations,
      StoreOrderRepository orders,
      StoreRepository stores,
      UserRepository users,
      @Qualifier("reservationClock") Clock clock) {
    this.reservations = reservations;
    this.orders = orders;
    this.stores = stores;
    this.users = users;
    this.clock = clock;
  }

  private Store owned(Long owner, Long store) {
    var user =
        users
            .findById(owner)
            .orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    if (user.getRole() != UserRole.OWNER)
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "사장님 권한이 필요합니다.");
    return stores.findByIdAndOwnerId(store, owner).orElseThrow(this::notFound);
  }

  public OwnerOrderResponse.Page list(
      Long owner,
      Long storeId,
      int page,
      int size,
      ApprovalStatus approval,
      ReservationStatus status) {
    var store = owned(owner, storeId);
    if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "page는 1 이상, size는 1~100으로 입력해 주세요.");
    LocalDateTime now = now();
    for (var r : reservations.findExpiredForStoreForUpdate(storeId, now)) expire(r, now);
    reservations.flush();
    var result = orders.findOwnerOrders(storeId, approval, status, PageRequest.of(page - 1, size));
    return new OwnerOrderResponse.Page(
        result.getContent().stream()
            .map(
                o ->
                    response(
                        o,
                        reservations.findById(o.getReservationId()).orElseThrow(this::notFound),
                        store,
                        now))
            .toList(),
        page,
        size,
        result.getTotalElements());
  }

  public OwnerOrderResponse detail(Long owner, Long storeId, Long orderId) {
    var store = owned(owner, storeId);
    var r = locked(orderId, storeId);
    var all = orders.findByReservationIdOrderByIdAsc(r.getId());
    var o = target(all, orderId, storeId);
    var now = now();
    r.expireIfDue(now, all);
    return response(o, r, store, now);
  }

  @Transactional(
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = ReservationExpiredException.class)
  public OwnerOrderResponse approve(Long owner, Long storeId, Long orderId) {
    return decide(owner, storeId, orderId, null);
  }

  @Transactional(
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = ReservationExpiredException.class)
  public OwnerOrderResponse reject(Long owner, Long storeId, Long orderId, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "거절 사유는 공백이 아닌 내용으로 1~1000자를 입력해 주세요.");
    return decide(owner, storeId, orderId, reason);
  }

  private OwnerOrderResponse decide(Long owner, Long storeId, Long orderId, String reason) {
    var store = owned(owner, storeId);
    var r = locked(orderId, storeId);
    // Load children only after the parent lock: a pre-lock entity read could hide a concurrent
    // response.
    var all = orders.findByReservationIdOrderByIdAsc(r.getId());
    var o = target(all, orderId, storeId);
    var now = now();
    r.expireIfDue(now, all);
    boolean approve = reason == null;
    if ((approve && o.getApprovalStatus() == ApprovalStatus.APPROVED)
        || (!approve
            && o.getApprovalStatus() == ApprovalStatus.REJECTED
            && reason.equals(o.getRejectionReason()))) return response(o, r, store, now);
    if ("APPROVAL_EXPIRED".equals(r.getFailureCode())) throw new ReservationExpiredException();
    if (r.getStatus() != ReservationStatus.PENDING)
      throw conflict("RESERVATION_NOT_PENDING", "이미 종료된 예약입니다.");
    if (o.getApprovalStatus() != ApprovalStatus.PENDING)
      throw conflict("ORDER_ALREADY_RESPONDED", "이미 처리한 주문입니다.");
    if (all.size() != 2 || all.stream().map(StoreOrder::getStoreId).distinct().count() != 2)
      throw conflict("INVALID_RESERVATION_STATE", "예약 주문 구성을 확인해 주세요.");
    var respondedAt = now.truncatedTo(ChronoUnit.SECONDS);
    if (approve) {
      o.approve(respondedAt);
      if (all.stream().allMatch(x -> x.getApprovalStatus() == ApprovalStatus.APPROVED)) r.confirm();
    } else {
      o.reject(respondedAt, reason);
      r.reject();
    }
    return response(o, r, store, now);
  }

  private Reservation locked(Long orderId, Long storeId) {
    Long reservationId = orders.findReservationId(orderId, storeId).orElseThrow(this::notFound);
    return reservations.findForUpdate(reservationId).orElseThrow(this::notFound);
  }

  private StoreOrder target(List<StoreOrder> all, Long orderId, Long storeId) {
    return all.stream()
        .filter(o -> o.getId().equals(orderId) && o.getStoreId().equals(storeId))
        .findFirst()
        .orElseThrow(this::notFound);
  }

  private void expire(Reservation r, LocalDateTime now) {
    r.expireIfDue(now, orders.findByReservationIdOrderByIdAsc(r.getId()));
  }

  private OwnerOrderResponse response(StoreOrder o, Reservation r, Store store, LocalDateTime now) {
    boolean canRespond =
        r.getStatus() == ReservationStatus.PENDING
            && o.getApprovalStatus() == ApprovalStatus.PENDING
            && now.isBefore(r.getApprovalDeadlineAt());
    return new OwnerOrderResponse(
        o.getId().toString(),
        r.getId().toString(),
        o.getStoreId().toString(),
        store.getName(),
        o.getProductId().toString(),
        o.getQuantity(),
        o.getUnitPriceSnapshot(),
        o.getProductNameSnapshot(),
        o.getPickupAddressSnapshot(),
        offset(o.getRequestedPickupAt()),
        o.getApprovalStatus().name(),
        offset(o.getRespondedAt()),
        o.getRejectionReason(),
        r.getStatus().name(),
        r.getFailureCode(),
        offset(r.getApprovalDeadlineAt()),
        r.getCustomerName(),
        r.getCustomerPhone(),
        r.getCustomerNote(),
        canRespond);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), KST);
  }

  private static OffsetDateTime offset(LocalDateTime time) {
    return time == null ? null : time.atZone(KST).toOffsetDateTime();
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "점포 또는 주문을 찾을 수 없습니다.");
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
