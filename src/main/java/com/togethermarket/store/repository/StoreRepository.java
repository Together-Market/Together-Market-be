package com.togethermarket.store.repository;

import com.togethermarket.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {
  java.util.Optional<Store> findByIdAndOwnerId(Long id, Long ownerId);
}
