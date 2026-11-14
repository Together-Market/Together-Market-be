package com.togethermarket.store.controller;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.dto.request.StoreWriteRequest;
import com.togethermarket.store.dto.response.*;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.service.OwnerStoreService;
import com.togethermarket.store.service.geocoding.AddressGeocoder;
import com.togethermarket.store.service.geocoding.KakaoAddressGeocoder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/owner/stores")
public class OwnerStoreController {
  private final OwnerStoreService service;
  private final OwnerAccess access;
  private final AddressGeocoder geocoder;

  public OwnerStoreController(
      OwnerStoreService service, OwnerAccess access, AddressGeocoder geocoder) {
    this.service = service;
    this.access = access;
    this.geocoder = geocoder;
  }

  @GetMapping("/geocode")
  public org.springframework.http.ResponseEntity<OwnerStoreEnvelope<GeocodeResponse>> geocode(
      HttpServletRequest request,
      @RequestParam(name = "address", required = false) String address) {
    access.requireOwner(request);
    String normalized = KakaoAddressGeocoder.address(address);
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(
            new OwnerStoreEnvelope<>(
                GeocodeResponse.preview(normalized, geocoder.lookup(normalized))));
  }

  @GetMapping
  public OwnerStoreEnvelope<OwnerStorePageResponse> list(
      HttpServletRequest request,
      @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return new OwnerStoreEnvelope<>(service.list(access.requireOwner(request), page, size));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OwnerStoreEnvelope<CreatedStoreResponse> create(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    Long ownerId = access.requireOwner(request);
    return new OwnerStoreEnvelope<>(service.create(ownerId, StoreWriteRequest.parse(body, true)));
  }

  @PatchMapping("/{store_id}")
  public OwnerStoreEnvelope<StoreResponse> update(
      HttpServletRequest request,
      @PathVariable("store_id") String storeId,
      @RequestBody Map<String, Object> body) {
    Long ownerId = access.requireOwner(request);
    Long id;
    try {
      if (!storeId.matches("[1-9][0-9]*")) throw new NumberFormatException();
      id = Long.valueOf(storeId);
    } catch (NumberFormatException ex) {
      throw OwnerStoreException.invalid("store_id", "양의 정수 ID여야 합니다.");
    }
    return new OwnerStoreEnvelope<>(
        service.update(ownerId, id, StoreWriteRequest.parse(body, false)));
  }
}
