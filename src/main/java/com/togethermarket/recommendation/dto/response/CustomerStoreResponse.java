package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.store.entity.HourType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CustomerStoreResponse(
    String id,
    String name,
    @JsonProperty("business_type") String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String phone,
    List<Hour> hours) {
  public CustomerStoreResponse {
    hours = List.copyOf(hours);
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Hour(
      Short weekday,
      @JsonProperty("target_date") LocalDate targetDate,
      @JsonProperty("hour_type") HourType hourType,
      @JsonProperty("is_closed") boolean closed,
      @JsonProperty("opens_at") @JsonFormat(pattern = "HH:mm:ss") LocalTime opensAt,
      @JsonProperty("closes_at") @JsonFormat(pattern = "HH:mm:ss") LocalTime closesAt,
      @JsonProperty("end_day_offset") Short endDayOffset) {}
}
