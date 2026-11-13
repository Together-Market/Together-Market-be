package com.togethermarket.store.service;

import com.togethermarket.store.dto.request.StoreWriteRequest;
import com.togethermarket.store.dto.response.*;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OwnerStoreService {
  private final StoreRepository stores;
  private final UserRepository users;

  public OwnerStoreService(StoreRepository stores, UserRepository users) {
    this.stores = stores;
    this.users = users;
  }

  private void requireOwner(Long ownerId) {
    if (ownerId == null)
      throw new OwnerStoreException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    var user =
        users
            .findById(ownerId)
            .orElseThrow(
                () ->
                    new OwnerStoreException(
                        HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    if (user.getRole() != UserRole.OWNER)
      throw new OwnerStoreException(HttpStatus.FORBIDDEN, "FORBIDDEN", "사장님 권한이 필요합니다.");
  }

  public OwnerStorePageResponse list(Long ownerId, int page, int size) {
    requireOwner(ownerId);
    if (page < 1) throw OwnerStoreException.invalid("page", "1 이상이어야 합니다.");
    if (size < 1 || size > 100) throw OwnerStoreException.invalid("size", "1~100이어야 합니다.");
    // JPA uses a long offset, but its JPA query adapter ultimately requires an int.
    if ((long) (page - 1) * size > Integer.MAX_VALUE)
      throw OwnerStoreException.invalid("page", "조회 가능한 페이지 범위를 초과했습니다.");
    var result =
        stores.findByOwnerId(
            ownerId, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
    return new OwnerStorePageResponse(
        result.map(StoreResponse::from).getContent(), page, size, result.getTotalElements());
  }

  @Transactional
  public CreatedStoreResponse create(Long ownerId, StoreWriteRequest request) {
    requireOwner(ownerId);
    request.validateCoordinates(null);
    Store store =
        stores.save(
            new Store(
                ownerId,
                request.name(),
                request.businessType(),
                request.address(),
                request.latitude(),
                request.longitude(),
                request.phone()));
    return CreatedStoreResponse.from(store);
  }

  @Transactional
  public StoreResponse update(Long ownerId, Long storeId, StoreWriteRequest request) {
    requireOwner(ownerId);
    // Lock serializes partial updates and avoids lost changes to unrelated fields.
    Store store =
        stores
            .findOwnedForUpdate(storeId, ownerId)
            .orElseThrow(
                () -> new OwnerStoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "점포를 찾을 수 없습니다."));
    request.validateCoordinates(store);
    store.updateDetails(
        request.has("name") ? request.name() : store.getName(),
        request.has("business_type") ? request.businessType() : store.getBusinessType(),
        request.has("address") ? request.address() : store.getAddress(),
        request.has("latitude") ? request.latitude() : store.getLatitude(),
        request.has("longitude") ? request.longitude() : store.getLongitude(),
        request.has("phone") ? request.phone() : store.getPhone());
    return StoreResponse.from(store);
  }
}
