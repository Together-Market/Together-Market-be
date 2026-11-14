package com.togethermarket.product.service;

import com.togethermarket.product.dto.request.ProductWriteRequest;
import com.togethermarket.product.dto.response.*;
import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.recommendation.repository.PurposeCategoryRepository;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OwnerProductService {
  private final ProductRepository products;
  private final ProductPurposeTagRepository tags;
  private final StoreRepository stores;
  private final UserRepository users;
  private final PurposeCategoryRepository categories;

  public OwnerProductService(
      ProductRepository products,
      ProductPurposeTagRepository tags,
      StoreRepository stores,
      UserRepository users,
      PurposeCategoryRepository categories) {
    this.products = products;
    this.tags = tags;
    this.stores = stores;
    this.users = users;
    this.categories = categories;
  }

  private OwnerStoreException notFound() {
    return new OwnerStoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
  }

  private void ownedStore(Long ownerId, Long storeId) {
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
    stores.findByIdAndOwnerId(storeId, ownerId).orElseThrow(this::notFound);
  }

  private void validateTags(List<Long> ids) {
    var found = categories.findAllById(ids);
    if (found.size() != ids.size() || found.stream().anyMatch(c -> c.getParentId() == null))
      throw OwnerStoreException.invalid("purpose_category_ids", "존재하는 중분류 ID만 사용할 수 있습니다.");
  }

  private List<Long> tagIds(Long id) {
    return tags.findByIdProductId(id).stream().map(t -> t.getId().getPurposeCategoryId()).toList();
  }

  public OwnerProductPageResponse list(
      Long owner, Long store, int page, int size, SaleStatus status) {
    ownedStore(owner, store);
    if (page < 1 || (long) (page - 1) * size > Integer.MAX_VALUE)
      throw OwnerStoreException.invalid("page", "조회 가능한 양의 페이지 번호여야 합니다.");
    if (size < 1 || size > 100) throw OwnerStoreException.invalid("size", "1~100이어야 합니다.");
    Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
    Page<Product> result =
        status == null
            ? products.findByStoreId(store, pageable)
            : products.findByStoreIdAndSaleStatus(store, status, pageable);
    List<Long> ids = result.getContent().stream().map(Product::getId).toList();
    Map<Long, List<Long>> grouped =
        ids.isEmpty()
            ? Map.of()
            : tags.findByIdProductIdIn(ids).stream()
                .collect(
                    Collectors.groupingBy(
                        t -> t.getId().getProductId(),
                        Collectors.mapping(
                            t -> t.getId().getPurposeCategoryId(), Collectors.toList())));
    return new OwnerProductPageResponse(
        result.stream()
            .map(p -> OwnerProductResponse.from(p, grouped.getOrDefault(p.getId(), List.of())))
            .toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Transactional
  public OwnerProductResponse create(Long owner, Long store, ProductWriteRequest request) {
    ownedStore(owner, store);
    validateTags(request.categoryIds());
    Product p =
        products.saveAndFlush(
            new Product(
                store,
                request.name(),
                request.description(),
                request.imageUrl(),
                request.priceAmount(),
                request.preparationMinutes(),
                request.saleStatus()));
    tags.saveAllAndFlush(
        request.categoryIds().stream().map(id -> new ProductPurposeTag(p.getId(), id)).toList());
    return OwnerProductResponse.from(p, request.categoryIds());
  }

  @Transactional
  public OwnerProductResponse update(
      Long owner, Long store, Long product, ProductWriteRequest request) {
    ownedStore(owner, store);
    Product p = products.findForUpdate(product, store).orElseThrow(this::notFound);
    if (request.has("purpose_category_ids")) validateTags(request.categoryIds());
    p.updateDetails(
        request.has("name") ? request.name() : p.getName(),
        request.has("description") ? request.description() : p.getDescription(),
        request.has("image_url") ? request.imageUrl() : p.getImageUrl(),
        request.has("price_amount") ? request.priceAmount() : p.getPriceAmount(),
        request.has("preparation_minutes")
            ? request.preparationMinutes()
            : p.getPreparationMinutes());
    List<Long> ids;
    if (request.has("purpose_category_ids")) {
      // Flush product changes before bulk deletion clears stale composite-key entities.
      tags.deleteForProduct(product);
      tags.saveAllAndFlush(
          request.categoryIds().stream().map(id -> new ProductPurposeTag(product, id)).toList());
      ids = request.categoryIds();
    } else {
      products.flush();
      ids = tagIds(product);
    }
    return OwnerProductResponse.from(p, ids);
  }

  @Transactional
  public OwnerProductResponse status(Long owner, Long store, Long product, SaleStatus status) {
    ownedStore(owner, store);
    Product p = products.findForUpdate(product, store).orElseThrow(this::notFound);
    p.changeSaleStatus(status);
    products.flush();
    return OwnerProductResponse.from(p, tagIds(product));
  }
}
