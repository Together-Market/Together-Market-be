package com.togethermarket.store.controller;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.dto.request.StoreHoursRequest;
import com.togethermarket.store.dto.response.*;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.service.OwnerStoreHoursService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/owner/stores/{store_id}/hours")
public class OwnerStoreHoursController {
  private final OwnerStoreHoursService service;
  private final OwnerAccess access;

  public OwnerStoreHoursController(OwnerStoreHoursService service, OwnerAccess access) {
    this.service = service;
    this.access = access;
  }

  private Long id(String value) {
    try {
      if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
      return Long.valueOf(value);
    } catch (NumberFormatException ex) {
      throw OwnerStoreException.invalid("store_id", "양의 정수 ID여야 합니다.");
    }
  }

  @GetMapping
  public OwnerStoreEnvelope<StoreHoursResponse> get(
      HttpServletRequest request, @PathVariable("store_id") String storeId) {
    Long ownerId = access.requireOwner(request);
    return new OwnerStoreEnvelope<>(service.get(ownerId, id(storeId)));
  }

  @PutMapping
  public OwnerStoreEnvelope<StoreHoursResponse> replace(
      HttpServletRequest request,
      @PathVariable("store_id") String storeId,
      @RequestBody Map<String, Object> body) {
    Long ownerId = access.requireOwner(request);
    return new OwnerStoreEnvelope<>(
        service.replace(ownerId, id(storeId), StoreHoursRequest.parse(body)));
  }
}
