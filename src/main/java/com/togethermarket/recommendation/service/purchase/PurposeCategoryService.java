package com.togethermarket.recommendation.service.purchase;

import com.togethermarket.recommendation.dto.response.PurposeCategoriesResponse;
import com.togethermarket.recommendation.service.purchase.PurposeCategorySource.Category;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PurposeCategoryService {
  private static final Comparator<Category> ORDER =
      Comparator.comparingInt(Category::displayOrder).thenComparing(Category::id);
  private final PurposeCategorySource source;

  public PurposeCategoryService(PurposeCategorySource source) {
    this.source = Objects.requireNonNull(source);
  }

  public PurposeCategoriesResponse categories() {
    var categories = snapshot();
    var roots =
        categories.values().stream()
            .filter(c -> c.parentId() == null && c.active())
            .sorted(ORDER)
            .map(
                root ->
                    new PurposeCategoriesResponse.Category(
                        root.id(),
                        root.name(),
                        categories.values().stream()
                            .filter(c -> root.id().equals(c.parentId()) && c.active())
                            .sorted(ORDER)
                            .map(
                                c ->
                                    new PurposeCategoriesResponse.Child(
                                        c.id(), c.parentId(), c.name()))
                            .toList()))
            .toList();
    return new PurposeCategoriesResponse(roots);
  }

  public Category requireActiveChild(Long id) {
    Map<Long, Category> categories = snapshot();
    Category category = categories.get(id);
    if (category == null) {
      throw new PurchaseInputException(
          "PURPOSE_CATEGORY_NOT_FOUND", "존재하지 않는 구매 목적입니다.", List.of());
    }
    if (category.parentId() == null
        || !category.active()
        || !categories.get(category.parentId()).active()) {
      throw new PurchaseInputException("INVALID_PURPOSE_CATEGORY", "활성 중분류를 선택해 주세요.", List.of());
    }
    return category;
  }

  private Map<Long, Category> snapshot() {
    var categories = new HashMap<Long, Category>();
    for (Category category : source.findAll()) {
      if (categories.putIfAbsent(category.id(), category) != null) {
        throw new IllegalStateException("Duplicate category ID from category source");
      }
    }
    for (Category category : categories.values()) {
      if (category.parentId() != null) {
        Category parent = categories.get(category.parentId());
        if (parent == null || parent.parentId() != null) {
          throw new IllegalStateException("Category source must provide a complete two-level tree");
        }
      }
    }
    return categories;
  }
}
