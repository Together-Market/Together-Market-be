package com.togethermarket.reservation.service;

import com.togethermarket.global.api.ApiException;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationPolicy {
  private final Duration window;

  public ReservationPolicy(@Value("${reservation.approval-window:}") String configured) {
    window = configured.isBlank() ? null : Duration.parse(configured);
    if (window != null && (window.isNegative() || window.isZero()))
      throw new IllegalArgumentException("reservation.approval-window must be positive");
  }

  public Instant deadline(Instant now, Instant latestPreparationStart) {
    if (window == null)
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "RESERVATION_POLICY_NOT_CONFIGURED",
          "예약 승인 마감 정책이 설정되지 않았습니다.");
    var proposed = now.plus(window);
    var deadline = proposed.isBefore(latestPreparationStart) ? proposed : latestPreparationStart;
    if (!deadline.isAfter(now))
      throw new ApiException(
          HttpStatus.CONFLICT, "APPROVAL_WINDOW_UNAVAILABLE", "승인에 필요한 시간이 부족합니다.");
    return deadline;
  }
}
