package com.togethermarket.store.repository;

import com.togethermarket.store.entity.StoreHour;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreHourRepository extends JpaRepository<StoreHour, Long> {
  java.util.List<StoreHour> findByStoreId(Long storeId);
}
