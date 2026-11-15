package com.togethermarket.product.image;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.togethermarket.global.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GcsProductImageStore implements ProductImageStore {
  private volatile Storage storage;

  private Storage storage() {
    Storage current = storage;
    if (current == null) {
      synchronized (this) {
        current = storage;
        if (current == null) storage = current = StorageOptions.getDefaultInstance().getService();
      }
    }
    return current;
  }

  @Override
  public void put(String bucket, String name, String contentType, byte[] bytes) {
    try {
      storage()
          .create(
              BlobInfo.newBuilder(BlobId.of(bucket, "product-images/" + name))
                  .setContentType(contentType)
                  .build(),
              bytes,
              Storage.BlobTargetOption.doesNotExist());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  @Override
  public StoredImage get(String bucket, String name) {
    try {
      var blob = storage().get(BlobId.of(bucket, "product-images/" + name));
      return blob == null ? null : new StoredImage(blob.getContentType(), blob.getContent());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_UNAVAILABLE", "이미지 저장소를 사용할 수 없습니다.");
  }
}
