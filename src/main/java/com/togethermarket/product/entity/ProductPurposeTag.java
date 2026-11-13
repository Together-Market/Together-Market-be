package com.togethermarket.product.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "product_purpose_tags")
public class ProductPurposeTag {
  @EmbeddedId private ProductPurposeTagId id;

  protected ProductPurposeTag() {}

  public ProductPurposeTag(Long productId, Long purposeCategoryId) {
    this.id = new ProductPurposeTagId(productId, purposeCategoryId);
  }

  public ProductPurposeTagId getId() {
    return id;
  }
}
