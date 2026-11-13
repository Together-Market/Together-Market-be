package com.togethermarket.product.repository;

import com.togethermarket.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
  java.util.Optional<Product> findByIdAndStoreId(Long id, Long storeId);

  @org.springframework.data.jpa.repository.Query(
      "select p.id from Product p where p.saleStatus = :status and p.priceAmount <= :budget order by p.id")
  java.util.List<Long> findRecommendationProductIds(
      @org.springframework.data.repository.query.Param("status")
          com.togethermarket.product.entity.SaleStatus status,
      @org.springframework.data.repository.query.Param("budget") java.math.BigDecimal budget,
      org.springframework.data.domain.Pageable pageable);
}
