package com.togethermarket.product.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "store_id", nullable = false)
  private Long storeId;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "description", nullable = false, columnDefinition = "text")
  private String description;

  @Column(name = "image_url", nullable = true, columnDefinition = "text")
  private String imageUrl;

  @Column(name = "price_amount", nullable = false, precision = 12, scale = 0)
  private BigDecimal priceAmount;

  @Column(name = "preparation_minutes", nullable = false)
  private Integer preparationMinutes;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "sale_status", nullable = false, length = 20)
  private SaleStatus saleStatus;

  protected Product() {}

  public Product(
      Long storeId,
      String name,
      String description,
      String imageUrl,
      BigDecimal priceAmount,
      Integer preparationMinutes,
      SaleStatus saleStatus) {
    this.storeId = storeId;
    this.name = name;
    this.description = description;
    this.imageUrl = imageUrl;
    this.priceAmount = priceAmount;
    this.preparationMinutes = preparationMinutes;
    this.saleStatus = saleStatus;
  }

  public void updateDetails(
      String name,
      String description,
      String imageUrl,
      BigDecimal priceAmount,
      Integer preparationMinutes) {
    this.name = name;
    this.description = description;
    this.imageUrl = imageUrl;
    this.priceAmount = priceAmount;
    this.preparationMinutes = preparationMinutes;
  }

  public void changeSaleStatus(SaleStatus status) {
    this.saleStatus = java.util.Objects.requireNonNull(status);
  }

  public Long getId() {
    return id;
  }

  public Long getStoreId() {
    return storeId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public BigDecimal getPriceAmount() {
    return priceAmount;
  }

  public Integer getPreparationMinutes() {
    return preparationMinutes;
  }

  public SaleStatus getSaleStatus() {
    return saleStatus;
  }
}
