package com.togethermarket.store.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.StoreHour;
import java.time.*;
import java.util.*;

public record StoreHoursResponse(@JsonProperty("store_id") String storeId, List<Item> items) {
  public record Item(
      Short weekday,
      @JsonProperty("target_date") LocalDate targetDate,
      @JsonProperty("hour_type") HourType hourType,
      @JsonProperty("is_closed") boolean closed,
      @JsonProperty("opens_at") @JsonFormat(pattern = "HH:mm:ss") LocalTime opensAt,
      @JsonProperty("closes_at") @JsonFormat(pattern = "HH:mm:ss") LocalTime closesAt,
      @JsonProperty("end_day_offset") Short endDayOffset) {}

  public static StoreHoursResponse from(Long storeId, List<StoreHour> hours) {
    var sorted =
        hours.stream()
            .sorted(
                Comparator.comparing(
                        StoreHour::getTargetDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StoreHour::getWeekday, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(h -> h.getHourType().name()));
    return new StoreHoursResponse(
        storeId.toString(),
        sorted
            .map(
                h ->
                    new Item(
                        h.getWeekday(),
                        h.getTargetDate(),
                        h.getHourType(),
                        h.getIsClosed(),
                        h.getOpensAt(),
                        h.getClosesAt(),
                        h.getEndDayOffset()))
            .toList());
  }
}
