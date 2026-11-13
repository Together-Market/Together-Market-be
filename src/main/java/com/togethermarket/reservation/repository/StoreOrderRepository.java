package com.togethermarket.reservation.repository;

import com.togethermarket.reservation.entity.StoreOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long> {}
