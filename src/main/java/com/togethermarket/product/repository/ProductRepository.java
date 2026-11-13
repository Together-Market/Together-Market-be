package com.togethermarket.product.repository;

import com.togethermarket.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
  java.util.Optional<Product> findByIdAndStoreId(Long id, Long storeId);
}
