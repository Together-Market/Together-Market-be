package com.togethermarket.product.image;

public interface ProductImageStore {
  record StoredImage(String contentType, byte[] bytes) {}

  void put(String bucket, String name, String contentType, byte[] bytes);

  StoredImage get(String bucket, String name);
}
