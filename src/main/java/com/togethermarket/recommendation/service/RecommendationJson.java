package com.togethermarket.recommendation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the AI wire contract independent of the application's global JSON settings. */
public final class RecommendationJson {
  private static final JsonMapper MAPPER = new JsonMapper();

  private RecommendationJson() {}

  public static String stringify(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static Map<String, Object> parse(String value) {
    return object(MAPPER.readValue(value, Object.class));
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)
        || map.keySet().stream().anyMatch(k -> !(k instanceof String))) {
      throw new IllegalArgumentException("JSON object required");
    }
    return (Map<String, Object>) value;
  }

  public static List<?> array(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("JSON array required");
    }
    return list;
  }

  public static String string(Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Nonempty JSON string required");
    }
    return text;
  }

  public static int integer(Object value) {
    if (!(value instanceof Integer) && !(value instanceof Long)) {
      throw new IllegalArgumentException("JSON integer required");
    }
    return Math.toIntExact(((Number) value).longValue());
  }

  public static double number(Object value) {
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      throw new IllegalArgumentException("Finite JSON number required");
    }
    return number.doubleValue();
  }

  public static String resource(String name) {
    try (var stream = RecommendationJson.class.getResourceAsStream("/recommendation/" + name)) {
      if (stream == null) {
        throw new IllegalStateException("Missing recommendation resource: " + name);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException error) {
      throw new IllegalStateException("Could not read recommendation resource", error);
    }
  }

  public static String promptVersion() {
    try {
      byte[] text =
          (resource("evaluation-prompt.txt") + "\n" + resource("evaluation-schema.json"))
              .getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
