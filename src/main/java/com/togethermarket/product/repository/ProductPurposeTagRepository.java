package com.togethermarket.product.repository;

import com.togethermarket.product.entity.ProductPurposeTag;
import com.togethermarket.product.entity.ProductPurposeTagId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPurposeTagRepository
    extends JpaRepository<ProductPurposeTag, ProductPurposeTagId> {
  java.util.List<ProductPurposeTag> findByIdProductIdIn(java.util.Collection<Long> productIds);
}
