package com.togethermarket.store.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stores")
public class Store {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "business_type", nullable = false, length = 100)
  private String businessType;

  @Column(name = "address", nullable = false, length = 500)
  private String address;

  @Column(name = "latitude", nullable = true, precision = 10, scale = 7)
  private BigDecimal latitude;

  @Column(name = "longitude", nullable = true, precision = 10, scale = 7)
  private BigDecimal longitude;

  @Column(name = "phone", nullable = false, length = 30)
  private String phone;

  protected Store() {}

  public Store(
      Long ownerId,
      String name,
      String businessType,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String phone) {
    this.ownerId = ownerId;
    this.name = name;
    this.businessType = businessType;
    this.address = address;
    this.latitude = latitude;
    this.longitude = longitude;
    this.phone = phone;
  }

  public void updateDetails(
      String name,
      String businessType,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String phone) {
    this.name = name;
    this.businessType = businessType;
    this.address = address;
    this.latitude = latitude;
    this.longitude = longitude;
    this.phone = phone;
  }

  public Long getId() {
    return id;
  }

  public Long getOwnerId() {
    return ownerId;
  }

  public String getName() {
    return name;
  }

  public String getBusinessType() {
    return businessType;
  }

  public String getAddress() {
    return address;
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }

  public String getPhone() {
    return phone;
  }
}
