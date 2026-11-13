package com.togethermarket.store.service;

import com.togethermarket.store.dto.request.StoreHoursRequest;
import com.togethermarket.store.dto.response.StoreHoursResponse;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OwnerStoreHoursService {
  private final StoreRepository stores;
  private final StoreHourRepository hours;
  private final UserRepository users;

  public OwnerStoreHoursService(
      StoreRepository stores, StoreHourRepository hours, UserRepository users) {
    this.stores = stores;
    this.hours = hours;
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

  private OwnerStoreException notFound() {
    return new OwnerStoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "점포를 찾을 수 없습니다.");
  }

  public StoreHoursResponse get(Long ownerId, Long storeId) {
    requireOwner(ownerId);
    stores.findByIdAndOwnerId(storeId, ownerId).orElseThrow(this::notFound);
    return StoreHoursResponse.from(storeId, hours.findByStoreId(storeId));
  }

  @Transactional
  public StoreHoursResponse replace(Long ownerId, Long storeId, StoreHoursRequest request) {
    requireOwner(ownerId);
    // The parent exists even when there are no hours. All replacement writers lock it first.
    stores.findOwnedForUpdate(storeId, ownerId).orElseThrow(this::notFound);
    hours.deleteForStore(storeId);
    var saved =
        hours.saveAllAndFlush(request.items().stream().map(i -> i.toEntity(storeId)).toList());
    return StoreHoursResponse.from(storeId, saved);
  }
}
