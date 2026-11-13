package com.togethermarket.store.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "public_store_records")
public class PublicStoreRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "source", nullable = false, length = 50)
  private String source;

  @Column(name = "external_id", nullable = true, length = 200)
  private String externalId;

  @Column(name = "source_row_number", nullable = true, length = 50)
  private String sourceRowNumber;

  @Column(name = "name", nullable = true, length = 200)
  private String name;

  @Column(name = "branch_name", nullable = true, length = 200)
  private String branchName;

  @Column(name = "business_type", nullable = true, length = 150)
  private String businessType;

  @Column(name = "address", nullable = true, length = 700)
  private String address;

  @Column(name = "latitude", nullable = true, precision = 10, scale = 7)
  private BigDecimal latitude;

  @Column(name = "longitude", nullable = true, precision = 10, scale = 7)
  private BigDecimal longitude;

  @Column(name = "last_seen_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime lastSeenAt;

  @Column(name = "matched_store_id", nullable = true)
  private Long matchedStoreId;

  protected PublicStoreRecord() {}

  public PublicStoreRecord(
      String source,
      String externalId,
      String sourceRowNumber,
      String name,
      String branchName,
      String businessType,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      LocalDateTime lastSeenAt,
      Long matchedStoreId) {
    this.source = source;
    this.externalId = externalId;
    this.sourceRowNumber = sourceRowNumber;
    this.name = name;
    this.branchName = branchName;
    this.businessType = businessType;
    this.address = address;
    this.latitude = latitude;
    this.longitude = longitude;
    this.lastSeenAt = lastSeenAt;
    this.matchedStoreId = matchedStoreId;
  }

  public Long getId() {
    return id;
  }

  public String getSource() {
    return source;
  }

  public String getExternalId() {
    return externalId;
  }

  public String getSourceRowNumber() {
    return sourceRowNumber;
  }

  public String getName() {
    return name;
  }

  public String getBranchName() {
    return branchName;
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

  public LocalDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public Long getMatchedStoreId() {
    return matchedStoreId;
  }
}
