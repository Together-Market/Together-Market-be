package com.togethermarket.reservation.controller;

import com.togethermarket.global.api.ApiResponse;
import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.reservation.dto.request.RejectOrderRequest;
import com.togethermarket.reservation.dto.response.OwnerOrderResponse;
import com.togethermarket.reservation.entity.*;
import com.togethermarket.reservation.service.OwnerOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/owner/stores/{store_id}/orders")
public class OwnerOrderController {
  private final OwnerOrderService service;
  private final OwnerAccess access;

  public OwnerOrderController(OwnerOrderService service, OwnerAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  public ApiResponse<OwnerOrderResponse.Page> list(
      HttpServletRequest request,
      @PathVariable("store_id") Long store,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(name = "approval_status", required = false) ApprovalStatus approval,
      @RequestParam(name = "reservation_status", required = false) ReservationStatus status) {
    return new ApiResponse<>(
        service.list(access.requireOwner(request), store, page, size, approval, status));
  }

  @GetMapping("/{order_id}")
  public ApiResponse<OwnerOrderResponse> detail(
      HttpServletRequest request,
      @PathVariable("store_id") Long store,
      @PathVariable("order_id") Long order) {
    return new ApiResponse<>(service.detail(access.requireOwner(request), store, order));
  }

  @PostMapping("/{order_id}/approve")
  public ApiResponse<OwnerOrderResponse> approve(
      HttpServletRequest request,
      @PathVariable("store_id") Long store,
      @PathVariable("order_id") Long order) {
    return new ApiResponse<>(service.approve(access.requireOwner(request), store, order));
  }

  @PostMapping("/{order_id}/reject")
  public ApiResponse<OwnerOrderResponse> reject(
      HttpServletRequest request,
      @PathVariable("store_id") Long store,
      @PathVariable("order_id") Long order,
      @Valid @RequestBody RejectOrderRequest input) {
    return new ApiResponse<>(
        service.reject(access.requireOwner(request), store, order, input.rejectionReason()));
  }
}
