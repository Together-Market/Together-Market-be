package com.togethermarket.store.repository;

import com.togethermarket.store.entity.PublicStoreRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicStoreRecordRepository extends JpaRepository<PublicStoreRecord, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select r from PublicStoreRecord r where r.id=:id")
  java.util.Optional<PublicStoreRecord> findForUpdate(
      @org.springframework.data.repository.query.Param("id") Long id);
}
