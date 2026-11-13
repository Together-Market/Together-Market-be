package com.togethermarket.product.controller;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.product.dto.request.ProductWriteRequest;
import com.togethermarket.product.dto.response.*;
import com.togethermarket.product.service.OwnerProductService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/owner/stores/{store_id}/products")
public class OwnerProductController {
  private final OwnerProductService service;
  private final OwnerAccess access;

  public OwnerProductController(OwnerProductService service, OwnerAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  public OwnerProductEnvelope<OwnerProductPageResponse> list(
      HttpServletRequest req,
      @PathVariable("store_id") String store,
      @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @RequestParam(name = "sale_status", required = false) String status) {
    Long owner = access.requireOwner(req);
    return new OwnerProductEnvelope<>(
        service.list(
            owner,
            ProductWriteRequest.id(store, "store_id"),
            page,
            size,
            status == null ? null : ProductWriteRequest.parseStatus(status)));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OwnerProductEnvelope<OwnerProductResponse> create(
      HttpServletRequest req,
      @PathVariable("store_id") String store,
      @RequestBody Map<String, Object> body) {
    Long owner = access.requireOwner(req);
    return new OwnerProductEnvelope<>(
        service.create(
            owner,
            ProductWriteRequest.id(store, "store_id"),
            ProductWriteRequest.parse(body, true)));
  }

  @PatchMapping("/{product_id}")
  public OwnerProductEnvelope<OwnerProductResponse> update(
      HttpServletRequest req,
      @PathVariable("store_id") String store,
      @PathVariable("product_id") String product,
      @RequestBody Map<String, Object> body) {
    Long owner = access.requireOwner(req);
    return new OwnerProductEnvelope<>(
        service.update(
            owner,
            ProductWriteRequest.id(store, "store_id"),
            ProductWriteRequest.id(product, "product_id"),
            ProductWriteRequest.parse(body, false)));
  }

  @PatchMapping("/{product_id}/sale-status")
  public OwnerProductEnvelope<OwnerProductResponse> status(
      HttpServletRequest req,
      @PathVariable("store_id") String store,
      @PathVariable("product_id") String product,
      @RequestBody Map<String, Object> body) {
    Long owner = access.requireOwner(req);
    return new OwnerProductEnvelope<>(
        service.status(
            owner,
            ProductWriteRequest.id(store, "store_id"),
            ProductWriteRequest.id(product, "product_id"),
            ProductWriteRequest.statusBody(body)));
  }
}
