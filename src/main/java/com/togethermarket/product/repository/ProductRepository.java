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

  org.springframework.data.domain.Page<Product> findByStoreId(
      Long storeId, org.springframework.data.domain.Pageable pageable);

  org.springframework.data.domain.Page<Product> findByStoreIdAndSaleStatus(
      Long storeId,
      com.togethermarket.product.entity.SaleStatus status,
      org.springframework.data.domain.Pageable pageable);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select p from Product p where p.id = :id and p.storeId = :storeId")
  java.util.Optional<Product> findForUpdate(
      @org.springframework.data.repository.query.Param("id") Long id,
      @org.springframework.data.repository.query.Param("storeId") Long storeId);
}
