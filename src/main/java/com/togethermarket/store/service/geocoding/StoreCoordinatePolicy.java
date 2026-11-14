package com.togethermarket.store.service.geocoding;

import com.togethermarket.store.dto.request.StoreWriteRequest;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.exception.OwnerStoreException;
import org.springframework.http.HttpStatus;

/** Kakao persistence is intentionally blocked; no configuration switch bypasses this policy. */
public final class StoreCoordinatePolicy {
  private StoreCoordinatePolicy() {}

  public static Coordinates forWrite(StoreWriteRequest request, Store existing) {
    if ("KAKAO".equals(request.coordinateSource())) throw storageNotAllowed();
    boolean addressChanged =
        existing == null
            || request.has("address") && !request.address().equals(existing.getAddress());
    boolean supplied = request.has("latitude") || request.has("longitude");
    if (!addressChanged && !supplied && !request.has("coordinate_source")) return null;
    if (supplied && request.has("latitude") != request.has("longitude"))
      throw OwnerStoreException.invalid("latitude", "위도와 경도를 함께 보내 주세요. 기존 좌표와 섞어 사용할 수 없습니다.");
    if (!Coordinates.usable(request.latitude(), request.longitude())) {
      if (request.latitude() == null || request.longitude() == null) throw storageNotAllowed();
      throw OwnerStoreException.invalid("latitude", "사용 가능한 좌표 한 쌍을 입력해 주세요. (0, 0)은 사용할 수 없습니다.");
    }
    return new Coordinates(request.latitude(), request.longitude());
  }

  public static OwnerStoreException storageNotAllowed() {
    return new OwnerStoreException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GEOCODE_STORAGE_NOT_ALLOWED",
        "카카오 조회 결과는 저장할 수 없습니다. 저장 가능한 출처의 좌표를 확보해 주세요.");
  }
}
