package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.repository.PurposeCategoryRepository;
import com.togethermarket.recommendation.service.purchase.PurposeCategorySource;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class JpaPurposeCategorySource implements PurposeCategorySource {
  private final PurposeCategoryRepository repository;

  public JpaPurposeCategorySource(PurposeCategoryRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<Category> findAll() {
    // Shared schema has no activation/display-order columns. All stored rows are usable; ID breaks
    // ties.
    return repository.findAll().stream()
        .map(p -> new Category(p.getId(), p.getParentId(), p.getName(), 0, true))
        .toList();
  }
}
