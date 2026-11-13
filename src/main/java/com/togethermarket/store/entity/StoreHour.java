package com.togethermarket.store.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "store_hours")
public class StoreHour {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "store_id", nullable = false)
  private Long storeId;

  @Column(name = "weekday", nullable = true)
  private Short weekday;

  @Column(name = "target_date", nullable = true)
  private LocalDate targetDate;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "hour_type", nullable = false, length = 20)
  private HourType hourType;

  @Column(name = "is_closed", nullable = false)
  private Boolean isClosed;

  @Column(name = "opens_at", nullable = true)
  private LocalTime opensAt;

  @Column(name = "closes_at", nullable = true)
  private LocalTime closesAt;

  @Column(name = "end_day_offset", nullable = false)
  private Short endDayOffset;

  protected StoreHour() {}

  public StoreHour(
      Long storeId,
      Short weekday,
      LocalDate targetDate,
      HourType hourType,
      Boolean isClosed,
      LocalTime opensAt,
      LocalTime closesAt,
      Short endDayOffset) {
    this.storeId = storeId;
    this.weekday = weekday;
    this.targetDate = targetDate;
    this.hourType = hourType;
    this.isClosed = isClosed;
    this.opensAt = opensAt;
    this.closesAt = closesAt;
    this.endDayOffset = endDayOffset;
  }

  public Long getId() {
    return id;
  }

  public Long getStoreId() {
    return storeId;
  }

  public Short getWeekday() {
    return weekday;
  }

  public LocalDate getTargetDate() {
    return targetDate;
  }

  public HourType getHourType() {
    return hourType;
  }

  public Boolean getIsClosed() {
    return isClosed;
  }

  public LocalTime getOpensAt() {
    return opensAt;
  }

  public LocalTime getClosesAt() {
    return closesAt;
  }

  public Short getEndDayOffset() {
    return endDayOffset;
  }
}
