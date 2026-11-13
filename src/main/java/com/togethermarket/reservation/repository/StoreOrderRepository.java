package com.togethermarket.reservation.repository;

import com.togethermarket.reservation.entity.StoreOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long> {
  java.util.List<StoreOrder> findByReservationIdOrderByIdAsc(Long reservationId);

  @org.springframework.data.jpa.repository.Query(
      "select o.reservationId from StoreOrder o where o.id=:id and o.storeId=:store")
  java.util.Optional<Long> findReservationId(
      @org.springframework.data.repository.query.Param("id") Long id,
      @org.springframework.data.repository.query.Param("store") Long store);

  @org.springframework.data.jpa.repository.Query(
      value =
          "select o from StoreOrder o, Reservation r where r.id=o.reservationId and o.storeId=:store and (:approval is null or o.approvalStatus=:approval) and (:reservation is null or r.status=:reservation) order by r.createdAt desc,r.id desc,o.id desc",
      countQuery =
          "select count(o) from StoreOrder o, Reservation r where r.id=o.reservationId and o.storeId=:store and (:approval is null or o.approvalStatus=:approval) and (:reservation is null or r.status=:reservation)")
  org.springframework.data.domain.Page<StoreOrder> findOwnerOrders(
      @org.springframework.data.repository.query.Param("store") Long store,
      @org.springframework.data.repository.query.Param("approval")
          com.togethermarket.reservation.entity.ApprovalStatus approval,
      @org.springframework.data.repository.query.Param("reservation")
          com.togethermarket.reservation.entity.ReservationStatus reservation,
      org.springframework.data.domain.Pageable pageable);
}
