package com.togethermarket.product.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.global.api.ApiException;
import com.togethermarket.global.api.ApiResponse;
import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.product.dto.request.ProductWriteRequest;
import com.togethermarket.store.repository.StoreRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductImageController {
  private final OwnerAccess access;
  private final StoreRepository stores;
  private final ProductImageStore images;
  private final String bucket;
  private final String publicBaseUrl;

  public ProductImageController(
      OwnerAccess access,
      StoreRepository stores,
      ProductImageStore images,
      @Value("${product.images.bucket:}") String bucket,
      @Value("${product.images.public-base-url:}") String publicBaseUrl) {
    this.access = access;
    this.stores = stores;
    this.images = images;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
  }

  public record UploadResponse(@JsonProperty("image_url") String imageUrl) {}

  @PostMapping(
      value = "/owner/stores/{store_id}/product-images",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<UploadResponse> upload(
      HttpServletRequest request,
      @PathVariable("store_id") String storeId,
      @RequestPart("file") MultipartFile file) {
    Long ownerId = access.requireOwner(request);
    Long id = ProductWriteRequest.id(storeId, "store_id");
    if (stores.findByIdAndOwnerId(id, ownerId).isEmpty())
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    if (bucket.isBlank() || publicBaseUrl.isBlank()) throw unavailable();
    URI base;
    try {
      base = URI.create(publicBaseUrl);
      if (!"https".equalsIgnoreCase(base.getScheme())
          || base.getHost() == null
          || base.getUserInfo() != null
          || base.getRawQuery() != null
          || base.getRawFragment() != null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      throw unavailable();
    }
    ProductImageValidator.Image image = ProductImageValidator.validate(file);
    String name = UUID.randomUUID() + image.extension();
    images.put(bucket, name, image.contentType(), image.bytes());
    return new ApiResponse<>(
        new UploadResponse(publicBaseUrl.replaceAll("/+$", "") + "/product-images/" + name));
  }

  @GetMapping("/product-images/{file_name}")
  public ResponseEntity<byte[]> read(@PathVariable("file_name") String fileName) {
    if (!fileName.matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)"))
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "이미지를 찾을 수 없습니다.");
    if (bucket.isBlank()) throw unavailable();
    ProductImageStore.StoredImage image = images.get(bucket, fileName);
    if (image == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "이미지를 찾을 수 없습니다.");
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(fileName.endsWith(".png") ? "image/png" : "image/jpeg"))
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
        .body(image.bytes());
  }

  private static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_UNAVAILABLE", "이미지 저장소를 사용할 수 없습니다.");
  }
}
