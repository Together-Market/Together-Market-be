package com.togethermarket.store.repository;

import com.togethermarket.store.entity.StoreHour;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreHourRepository extends JpaRepository<StoreHour, Long> {
  java.util.List<StoreHour> findByStoreId(Long storeId);

  java.util.List<StoreHour> findByStoreIdIn(Collection<Long> storeIds);

  @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "delete from StoreHour h where h.storeId = :storeId")
  int deleteForStore(@org.springframework.data.repository.query.Param("storeId") Long storeId);
}
