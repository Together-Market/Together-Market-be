package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.product.repository.ProductPurposeTagRepository;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.recommendation.service.candidate.CandidateData.Product;
import com.togethermarket.recommendation.service.candidate.CatalogSource;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class JpaCatalogSource implements CatalogSource {
  private final ProductRepository products;
  private final StoreRepository stores;
  private final ProductPurposeTagRepository tags;

  public JpaCatalogSource(
      ProductRepository products, StoreRepository stores, ProductPurposeTagRepository tags) {
    this.products = products;
    this.stores = stores;
    this.tags = tags;
  }

  @Override
  public Map<Long, Product> findProducts(Set<Long> ids) {
    if (ids.isEmpty()) return Map.of();
    var loaded = products.findAllById(ids);
    var byStore =
        stores
            .findAllById(loaded.stream().map(p -> p.getStoreId()).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(Store::getId, Function.identity()));
    var byProduct = new HashMap<Long, Set<Long>>();
    for (var tag : tags.findByIdProductIdIn(ids)) {
      byProduct
          .computeIfAbsent(tag.getId().getProductId(), k -> new HashSet<>())
          .add(tag.getId().getPurposeCategoryId());
    }
    var result = new HashMap<Long, Product>();
    for (var p : loaded) {
      Store s = byStore.get(p.getStoreId());
      if (s == null) throw new IllegalStateException("Catalog store is missing");
      result.put(
          p.getId(),
          new Product(
              p.getId(),
              s.getId(),
              p.getName(),
              p.getDescription(),
              s.getBusinessType(),
              p.getPriceAmount().longValueExact(),
              byProduct.getOrDefault(p.getId(), Set.of()),
              s.getLatitude() == null ? null : s.getLatitude().doubleValue(),
              s.getLongitude() == null ? null : s.getLongitude().doubleValue()));
    }
    return Map.copyOf(result);
  }
}
