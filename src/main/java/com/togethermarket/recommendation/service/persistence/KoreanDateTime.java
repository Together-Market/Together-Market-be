package com.togethermarket.recommendation.service.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Team agreement: MySQL DATETIME stores KST, with second precision. */
public final class KoreanDateTime {
  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private KoreanDateTime() {}

  public static LocalDateTime toDatabase(Instant value) {
    return LocalDateTime.ofInstant(value, ZONE).truncatedTo(ChronoUnit.SECONDS);
  }

  public static Instant toInstant(LocalDateTime value) {
    return value.atZone(ZONE).toInstant();
  }

  public static OffsetDateTime toResponse(LocalDateTime value) {
    return value.atZone(ZONE).toOffsetDateTime();
  }
}
