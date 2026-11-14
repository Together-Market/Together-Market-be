package com.togethermarket.reservation.repository;

import com.togethermarket.reservation.entity.*;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Reservation> findByCustomerIdAndRequestKey(Long customerId, String requestKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Reservation> findByIdAndCustomerId(Long id, Long customerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select r from Reservation r where r.customerId=:customer and r.status=com.togethermarket.reservation.entity.ReservationStatus.PENDING and r.approvalDeadlineAt<=:now order by r.id")
  List<Reservation> findExpiredForUpdate(
      @Param("customer") Long customer, @Param("now") LocalDateTime now);

  Page<Reservation> findByCustomerId(Long customerId, Pageable pageable);

  Page<Reservation> findByCustomerIdAndStatus(
      Long customerId, ReservationStatus status, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Reservation r where r.id=:id")
  Optional<Reservation> findForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select r from Reservation r where r.status=com.togethermarket.reservation.entity.ReservationStatus.PENDING and r.approvalDeadlineAt<=:now and exists (select o.id from StoreOrder o where o.reservationId=r.id and o.storeId=:store) order by r.id")
  List<Reservation> findExpiredForStoreForUpdate(
      @Param("store") Long store, @Param("now") LocalDateTime now);

  @Query(
      "select r.id from Reservation r where r.status=com.togethermarket.reservation.entity.ReservationStatus.PENDING and r.approvalDeadlineAt<=:now order by r.approvalDeadlineAt,r.id")
  List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);
}
