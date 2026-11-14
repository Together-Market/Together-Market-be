package com.togethermarket.reservation.service;

import com.togethermarket.global.api.ApiException;
import org.springframework.http.HttpStatus;

/** The expiry transition must commit even though the late decision returns HTTP 409. */
public class ReservationExpiredException extends ApiException {
  public ReservationExpiredException() {
    super(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "승인 기한이 만료되었습니다.");
  }
}
