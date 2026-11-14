package com.togethermarket.product.repository;

import com.togethermarket.product.entity.ProductPurposeTag;
import com.togethermarket.product.entity.ProductPurposeTagId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPurposeTagRepository
    extends JpaRepository<ProductPurposeTag, ProductPurposeTagId> {
  java.util.List<ProductPurposeTag> findByIdProductId(Long productId);

  java.util.List<ProductPurposeTag> findByIdProductIdIn(java.util.Collection<Long> productIds);

  @org.springframework.data.jpa.repository.Modifying(
      flushAutomatically = true,
      clearAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "delete from ProductPurposeTag t where t.id.productId = :productId")
  int deleteForProduct(
      @org.springframework.data.repository.query.Param("productId") Long productId);
}
