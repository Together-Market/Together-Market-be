package com.togethermarket.store.repository;

import com.togethermarket.store.entity.Store;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, Long> {
  Optional<Store> findByIdAndOwnerId(Long id, Long ownerId);

  Page<Store> findByOwnerId(Long ownerId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Store s where s.id = :id and s.ownerId = :ownerId")
  Optional<Store> findOwnedForUpdate(@Param("id") Long id, @Param("ownerId") Long ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Store s where s.id = :id")
  Optional<Store> findForReservation(@Param("id") Long id);
}
