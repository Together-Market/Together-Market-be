package com.togethermarket.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProductPurposeTagId implements Serializable {
  private static final long serialVersionUID = 1L;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "purpose_category_id", nullable = false)
  private Long purposeCategoryId;

  protected ProductPurposeTagId() {}

  public ProductPurposeTagId(Long productId, Long purposeCategoryId) {
    this.productId = Objects.requireNonNull(productId);
    this.purposeCategoryId = Objects.requireNonNull(purposeCategoryId);
  }

  public Long getProductId() {
    return productId;
  }

  public Long getPurposeCategoryId() {
    return purposeCategoryId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ProductPurposeTagId that)) return false;
    return Objects.equals(productId, that.productId)
        && Objects.equals(purposeCategoryId, that.purposeCategoryId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productId, purposeCategoryId);
  }
}
