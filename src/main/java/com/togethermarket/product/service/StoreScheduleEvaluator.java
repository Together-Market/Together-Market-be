package com.togethermarket.product.service;

import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.StoreHour;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StoreScheduleEvaluator {
  public boolean allows(List<StoreHour> schedules, HourType type, LocalDateTime pickupAt) {
    LocalDate date = pickupAt.toLocalDate();
    // An explicit closure today also blocks an overnight opening from the previous day.
    if (select(schedules, type, date).stream()
        .anyMatch(h -> Boolean.TRUE.equals(h.getIsClosed()))) {
      return false;
    }
    return covers(select(schedules, type, date), date, pickupAt)
        || covers(select(schedules, type, date.minusDays(1)), date.minusDays(1), pickupAt);
  }

  private List<StoreHour> select(List<StoreHour> schedules, HourType type, LocalDate date) {
    List<StoreHour> exceptions =
        schedules.stream()
            .filter(h -> h.getHourType() == type && date.equals(h.getTargetDate()))
            .toList();
    if (!exceptions.isEmpty()) {
      return exceptions;
    }
    return schedules.stream()
        .filter(
            h ->
                h.getHourType() == type
                    && h.getTargetDate() == null
                    && h.getWeekday() != null
                    && h.getWeekday() == date.getDayOfWeek().getValue())
        .toList();
  }

  private boolean covers(List<StoreHour> schedules, LocalDate date, LocalDateTime pickupAt) {
    return schedules.stream()
        .anyMatch(
            h -> {
              if (!Boolean.FALSE.equals(h.getIsClosed())
                  || h.getOpensAt() == null
                  || h.getClosesAt() == null
                  || h.getEndDayOffset() == null) {
                return false;
              }
              LocalDateTime start = date.atTime(h.getOpensAt());
              LocalDateTime end = date.plusDays(h.getEndDayOffset()).atTime(h.getClosesAt());
              return !pickupAt.isBefore(start) && pickupAt.isBefore(end);
            });
  }
}
