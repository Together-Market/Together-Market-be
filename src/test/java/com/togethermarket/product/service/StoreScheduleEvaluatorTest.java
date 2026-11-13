package com.togethermarket.product.service;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.StoreHour;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreScheduleEvaluatorTest {
  private final StoreScheduleEvaluator evaluator = new StoreScheduleEvaluator();
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

  private StoreHour weekly(int day, String start, String end, int offset) {
    return new StoreHour(
        1L,
        (short) day,
        null,
        HourType.BUSINESS,
        false,
        LocalTime.parse(start),
        LocalTime.parse(end),
        (short) offset);
  }

  @Test
  void missingScheduleFailsClosed() {
    assertFalse(evaluator.allows(List.of(), HourType.BUSINESS, MONDAY.atTime(12, 0)));
  }

  @Test
  void openingIncludedClosingExcluded() {
    var schedule = List.of(weekly(1, "09:00", "18:00", 0));
    assertTrue(evaluator.allows(schedule, HourType.BUSINESS, MONDAY.atTime(9, 0)));
    assertFalse(evaluator.allows(schedule, HourType.BUSINESS, MONDAY.atTime(18, 0)));
    assertFalse(evaluator.allows(schedule, HourType.PICKUP, MONDAY.atTime(12, 0)));
  }

  @Test
  void dateExceptionReplacesWeeklyRule() {
    var override =
        new StoreHour(
            1L,
            null,
            MONDAY,
            HourType.BUSINESS,
            false,
            LocalTime.NOON,
            LocalTime.of(14, 0),
            (short) 0);
    var schedules = List.of(weekly(1, "09:00", "18:00", 0), override);
    assertFalse(evaluator.allows(schedules, HourType.BUSINESS, MONDAY.atTime(10, 0)));
    assertTrue(evaluator.allows(schedules, HourType.BUSINESS, MONDAY.atTime(13, 0)));
  }

  @Test
  void previousDayOvernightRuleAndClosureAreHandled() {
    var overnight = weekly(1, "22:00", "02:00", 1);
    LocalDateTime tuesday = MONDAY.plusDays(1).atTime(1, 0);
    assertTrue(evaluator.allows(List.of(overnight), HourType.BUSINESS, tuesday));
    var closure =
        new StoreHour(1L, null, MONDAY.plusDays(1), HourType.BUSINESS, true, null, null, (short) 0);
    assertFalse(evaluator.allows(List.of(overnight, closure), HourType.BUSINESS, tuesday));
  }
}
