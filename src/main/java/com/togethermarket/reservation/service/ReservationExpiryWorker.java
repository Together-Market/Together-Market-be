package com.togethermarket.reservation.service;

import com.togethermarket.reservation.repository.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReservationExpiryWorker {
  private final ReservationRepository reservations;
  private final StoreOrderRepository orders;
  private final Clock clock;

  public ReservationExpiryWorker(
      ReservationRepository reservations, StoreOrderRepository orders, Clock clock) {
    this.reservations = reservations;
    this.orders = orders;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean expire(Long id) {
    var saved = reservations.findForUpdate(id);
    if (saved.isEmpty()) return false;
    var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    var r = saved.get();
    if (r.getStatus() != com.togethermarket.reservation.entity.ReservationStatus.PENDING
        || now.isBefore(r.getApprovalDeadlineAt())) return false;
    return r.expireIfDue(now, orders.findByReservationIdOrderByIdAsc(id));
  }
}
