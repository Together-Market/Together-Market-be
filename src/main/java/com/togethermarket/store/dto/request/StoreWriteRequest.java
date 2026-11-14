package com.togethermarket.store.dto.request;

import com.togethermarket.store.exception.OwnerStoreException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Presence is kept separately so PATCH null and omitted fields are not conflated. */
public record StoreWriteRequest(
    String name,
    String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String phone,
    String coordinateSource,
    Set<String> fields) {
  private static final Set<String> ALLOWED =
      Set.of(
          "name",
          "business_type",
          "address",
          "latitude",
          "longitude",
          "phone",
          "coordinate_source");

  public StoreWriteRequest {
    fields = Set.copyOf(fields);
  }

  public static StoreWriteRequest parse(Map<String, Object> body, boolean create) {
    if (body == null || body.isEmpty())
      throw OwnerStoreException.invalid("body", "수정 또는 등록할 값을 입력해 주세요.");
    for (String key : body.keySet())
      if (!ALLOWED.contains(key)) throw OwnerStoreException.invalid(key, "허용되지 않은 필드입니다.");
    if (create)
      for (String key : Set.of("name", "business_type", "address", "phone"))
        if (!body.containsKey(key)) throw OwnerStoreException.invalid(key, "필수 필드입니다.");
    return new StoreWriteRequest(
        text(body, "name", 150),
        text(body, "business_type", 100),
        text(body, "address", 500),
        coordinate(body, "latitude", 90),
        coordinate(body, "longitude", 180),
        text(body, "phone", 30),
        source(body),
        body.keySet());
  }

  private static String source(Map<String, Object> body) {
    if (!body.containsKey("coordinate_source")) return "PROVIDED";
    Object value = body.get("coordinate_source");
    if (!"PROVIDED".equals(value) && !"KAKAO".equals(value))
      throw OwnerStoreException.invalid("coordinate_source", "PROVIDED 또는 KAKAO여야 합니다.");
    return (String) value;
  }

  private static String text(Map<String, Object> body, String key, int max) {
    if (!body.containsKey(key)) return null;
    if (!(body.get(key) instanceof String s) || s.isBlank() || s.length() > max)
      throw OwnerStoreException.invalid(key, "비어 있지 않은 " + max + "자 이하 문자열이어야 합니다.");
    return s;
  }

  private static BigDecimal coordinate(Map<String, Object> body, String key, int limit) {
    Object raw = body.get(key);
    if (raw == null) return null;
    if (!(raw instanceof Number)) throw OwnerStoreException.invalid(key, "숫자 또는 null이어야 합니다.");
    BigDecimal value;
    try {
      value = new BigDecimal(raw.toString());
    } catch (NumberFormatException ex) {
      throw OwnerStoreException.invalid(key, "유효한 숫자여야 합니다.");
    }
    if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0
        || value.stripTrailingZeros().scale() > 7)
      throw OwnerStoreException.invalid(key, "좌표 범위와 소수점 7자리 제한을 확인해 주세요.");
    return value;
  }

  public boolean has(String field) {
    return fields.contains(field);
  }
}
