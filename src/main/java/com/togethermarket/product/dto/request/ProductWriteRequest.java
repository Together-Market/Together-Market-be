package com.togethermarket.product.dto.request;

import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.store.exception.OwnerStoreException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record ProductWriteRequest(
    String name,
    String description,
    String imageUrl,
    BigDecimal priceAmount,
    Integer preparationMinutes,
    SaleStatus saleStatus,
    List<Long> categoryIds,
    Set<String> fields) {
  public ProductWriteRequest {
    fields = Set.copyOf(fields);
    if (categoryIds != null) categoryIds = List.copyOf(categoryIds);
  }

  private static final Set<String> CREATE =
      Set.of(
          "name",
          "description",
          "image_url",
          "price_amount",
          "preparation_minutes",
          "sale_status",
          "purpose_category_ids");
  private static final Set<String> PATCH =
      Set.of(
          "name",
          "description",
          "image_url",
          "price_amount",
          "preparation_minutes",
          "purpose_category_ids");

  public boolean has(String field) {
    return fields.contains(field);
  }

  public static ProductWriteRequest parse(Map<String, Object> body, boolean create) {
    Set<String> allowed = create ? CREATE : PATCH;
    if (body == null || body.isEmpty()) throw invalid("body", "입력값이 필요합니다.");
    for (String key : body.keySet())
      if (!allowed.contains(key)) throw invalid(key, "허용되지 않은 필드입니다.");
    if (create && !body.keySet().equals(CREATE))
      throw invalid("body", "등록 필드를 모두 입력해 주세요. 이미지가 없으면 image_url은 null입니다.");
    String name = text(body, "name", 200, false),
        desc = text(body, "description", 65535, true),
        url = text(body, "image_url", 65535, true);
    if (body.containsKey("image_url") && body.get("image_url") == null) url = null;
    if (url != null) {
      try {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null) throw new IllegalArgumentException();
      } catch (IllegalArgumentException ex) {
        throw invalid("image_url", "HTTPS URL 또는 null이어야 합니다.");
      }
    }
    BigDecimal price =
        body.containsKey("price_amount")
            ? number(body.get("price_amount"), "price_amount", 999999999999L)
            : null;
    Integer prep =
        body.containsKey("preparation_minutes")
            ? number(body.get("preparation_minutes"), "preparation_minutes", Integer.MAX_VALUE)
                .intValueExact()
            : null;
    SaleStatus status =
        body.containsKey("sale_status") ? parseStatus(body.get("sale_status")) : null;
    List<Long> ids = null;
    if (body.containsKey("purpose_category_ids")) {
      if (!(body.get("purpose_category_ids") instanceof List<?> list) || list.isEmpty())
        throw invalid("purpose_category_ids", "중분류 ID를 1개 이상 입력해 주세요.");
      ids = new ArrayList<>();
      Set<Long> seen = new HashSet<>();
      for (Object raw : list) {
        Long id = id(raw, "purpose_category_ids");
        if (!seen.add(id)) throw invalid("purpose_category_ids", "중복 태그는 허용하지 않습니다.");
        ids.add(id);
      }
    }
    return new ProductWriteRequest(name, desc, url, price, prep, status, ids, body.keySet());
  }

  private static String text(Map<String, Object> body, String key, int max, boolean bytes) {
    if (!body.containsKey(key) || (key.equals("image_url") && body.get(key) == null)) return null;
    if (!(body.get(key) instanceof String s)
        || s.isBlank()
        || (bytes ? s.getBytes(StandardCharsets.UTF_8).length : s.length()) > max)
      throw invalid(key, "비어 있지 않은 문자열이어야 하며 DB 길이 제한을 초과할 수 없습니다.");
    return s;
  }

  private static BigDecimal number(Object raw, String field, long max) {
    if (!(raw instanceof Number)) throw invalid(field, "0 이상 정수여야 합니다.");
    try {
      BigDecimal value = new BigDecimal(raw.toString());
      if (value.signum() < 0
          || value.stripTrailingZeros().scale() > 0
          || value.compareTo(BigDecimal.valueOf(max)) > 0) throw new IllegalArgumentException();
      return value.setScale(0);
    } catch (IllegalArgumentException | ArithmeticException ex) {
      throw invalid(field, "0~" + max + " 정수여야 합니다.");
    }
  }

  public static Long id(Object raw, String field) {
    try {
      if (!(raw instanceof String s) || !s.matches("[1-9][0-9]*"))
        throw new NumberFormatException();
      return Long.valueOf((String) raw);
    } catch (NumberFormatException ex) {
      throw invalid(field, "양의 정수 문자열 ID여야 합니다.");
    }
  }

  public static SaleStatus parseStatus(Object value) {
    try {
      return SaleStatus.valueOf((String) value);
    } catch (RuntimeException ex) {
      throw invalid("sale_status", "ON_SALE / SOLD_OUT / PAUSED 중 하나여야 합니다.");
    }
  }

  public static SaleStatus statusBody(Map<String, Object> body) {
    if (body == null || !body.keySet().equals(Set.of("sale_status")))
      throw invalid("body", "sale_status만 입력해 주세요.");
    return parseStatus(body.get("sale_status"));
  }

  private static OwnerStoreException invalid(String field, String reason) {
    return OwnerStoreException.invalid(field, reason);
  }
}
