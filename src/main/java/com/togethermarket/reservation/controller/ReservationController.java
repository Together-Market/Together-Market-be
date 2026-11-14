package com.togethermarket.reservation.controller;

import com.togethermarket.global.api.*;
import com.togethermarket.global.auth.LoginUser;
import com.togethermarket.reservation.dto.request.CreateReservationRequest;
import com.togethermarket.reservation.dto.response.ReservationResponse;
import com.togethermarket.reservation.entity.ReservationStatus;
import com.togethermarket.reservation.service.ReservationService;
import com.togethermarket.user.entity.UserRole;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
public class ReservationController {
  private final ReservationService service;

  public ReservationController(ReservationService service) {
    this.service = service;
  }

  private Long customer(LoginUser user) {
    if (user == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    if (user.role() != UserRole.CUSTOMER)
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "고객 권한이 필요합니다.");
    return user.userId();
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ReservationResponse>> create(
      @AuthenticationPrincipal LoginUser user,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody CreateReservationRequest input) {
    var result = service.create(customer(user), key, input);
    return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(new ApiResponse<>(result.reservation()));
  }

  @GetMapping
  public ApiResponse<ReservationResponse.Page> list(
      @AuthenticationPrincipal LoginUser user,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) ReservationStatus status) {
    return new ApiResponse<>(service.list(customer(user), page, size, status));
  }

  @GetMapping("/{id}")
  public ApiResponse<ReservationResponse> detail(
      @AuthenticationPrincipal LoginUser user, @PathVariable Long id) {
    return new ApiResponse<>(service.detail(customer(user), id));
  }
}
