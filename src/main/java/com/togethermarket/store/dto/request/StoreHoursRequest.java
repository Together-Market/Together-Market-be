package com.togethermarket.store.dto.request;

import com.togethermarket.store.entity.HourType;
import com.togethermarket.store.entity.StoreHour;
import com.togethermarket.store.exception.OwnerStoreException;
import java.time.*;
import java.util.*;

public record StoreHoursRequest(List<Item> items) {
  public StoreHoursRequest {
    items = List.copyOf(items);
  }

  public record Item(
      Short weekday,
      LocalDate targetDate,
      HourType hourType,
      boolean closed,
      LocalTime opensAt,
      LocalTime closesAt,
      short endDayOffset) {
    public StoreHour toEntity(Long storeId) {
      return new StoreHour(
          storeId, weekday, targetDate, hourType, closed, opensAt, closesAt, endDayOffset);
    }
  }

  private record Key(Short weekday, LocalDate targetDate, HourType type) {}

  private static final Set<String> FIELDS =
      Set.of(
          "weekday",
          "target_date",
          "hour_type",
          "is_closed",
          "opens_at",
          "closes_at",
          "end_day_offset");

  public static StoreHoursRequest parse(Map<String, Object> body) {
    if (body == null
        || !body.keySet().equals(Set.of("items"))
        || !(body.get("items") instanceof List<?> rows))
      throw OwnerStoreException.invalid("items", "items 배열만 입력해 주세요.");
    List<Item> items = new ArrayList<>();
    Set<Key> seen = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      String field = "items[" + i + "]";
      if (!(rows.get(i) instanceof Map<?, ?> row) || !row.keySet().equals(FIELDS))
        throw OwnerStoreException.invalid(field, "일정의 7개 필드를 모두 입력해 주세요. 알 수 없는 필드는 허용하지 않습니다.");
      Integer day = integer(row.get("weekday"), field + ".weekday", true);
      if (day != null && (day < 1 || day > 7))
        throw OwnerStoreException.invalid(field + ".weekday", "요일은 1~7이어야 합니다.");
      LocalDate date = date(row.get("target_date"), field + ".target_date");
      if ((day == null) == (date == null))
        throw OwnerStoreException.invalid(field, "요일과 특정일 중 정확히 하나만 입력해 주세요.");
      HourType type;
      try {
        type = HourType.valueOf((String) row.get("hour_type"));
      } catch (RuntimeException ex) {
        throw OwnerStoreException.invalid(field + ".hour_type", "BUSINESS 또는 PICKUP이어야 합니다.");
      }
      if (!(row.get("is_closed") instanceof Boolean closed))
        throw OwnerStoreException.invalid(field + ".is_closed", "boolean이어야 합니다.");
      LocalTime start = time(row.get("opens_at"), field + ".opens_at"),
          end = time(row.get("closes_at"), field + ".closes_at");
      int offset = integer(row.get("end_day_offset"), field + ".end_day_offset", false);
      if (closed) {
        if (start != null || end != null || offset != 0)
          throw OwnerStoreException.invalid(field, "휴무는 시작·종료시간 null, 종료일 가산일수 0이어야 합니다.");
      } else {
        if (start == null || end == null || offset < 0 || offset > 1)
          throw OwnerStoreException.invalid(field, "운영시간과 0 또는 1의 종료일 가산일수가 필요합니다.");
        long seconds = end.toSecondOfDay() + offset * 86400L - start.toSecondOfDay();
        if (seconds <= 0 || seconds > 86400)
          throw OwnerStoreException.invalid(field, "운영 구간은 0시간 초과 24시간 이하여야 합니다.");
      }
      Short weekday = day == null ? null : day.shortValue();
      if (!seen.add(new Key(weekday, date, type)))
        throw OwnerStoreException.invalid(field, "같은 요일 또는 특정일의 시간 구분이 중복됩니다.");
      items.add(new Item(weekday, date, type, closed, start, end, (short) offset));
    }
    return new StoreHoursRequest(items);
  }

  private static Integer integer(Object raw, String field, boolean nullable) {
    if (raw == null && nullable) return null;
    if (!(raw instanceof Number) || !raw.toString().matches("-?[0-9]+"))
      throw OwnerStoreException.invalid(field, "정수여야 합니다.");
    try {
      return Integer.valueOf(raw.toString());
    } catch (NumberFormatException ex) {
      throw OwnerStoreException.invalid(field, "정수 범위를 초과했습니다.");
    }
  }

  private static LocalDate date(Object raw, String field) {
    if (raw == null) return null;
    try {
      if (!(raw instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      LocalDate date = LocalDate.parse((String) raw);
      if (date.getYear() < 1000) throw new IllegalArgumentException();
      return date;
    } catch (RuntimeException ex) {
      throw OwnerStoreException.invalid(field, "유효한 YYYY-MM-DD 형식이어야 합니다.");
    }
  }

  private static LocalTime time(Object raw, String field) {
    if (raw == null) return null;
    try {
      if (!(raw instanceof String s) || !s.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}"))
        throw new IllegalArgumentException();
      return LocalTime.parse((String) raw);
    } catch (RuntimeException ex) {
      throw OwnerStoreException.invalid(field, "유효한 HH:mm:ss 형식이어야 합니다.");
    }
  }
}
