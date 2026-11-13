package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.recommendation.entity.RecommendationStatus;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.RecommendationInput.Context;
import com.togethermarket.recommendation.service.candidate.CandidateData.Purchase;
import com.togethermarket.recommendation.service.candidate.CandidateGenerator.Preferences;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class JpaRecommendationInputSource {
  private final PurchaseRequestRepository purchases;
  private final PurposeCategoryRepository categories;
  private final ProductRepository products;
  private final PurchaseAnswerCodec answers;
  private final int productLimit;

  public JpaRecommendationInputSource(
      PurchaseRequestRepository purchases,
      PurposeCategoryRepository categories,
      ProductRepository products,
      PurchaseAnswerCodec answers,
      int productLimit) {
    if (productLimit < 2) throw new IllegalArgumentException("At least two products required");
    this.purchases = purchases;
    this.categories = categories;
    this.products = products;
    this.answers = answers;
    this.productLimit = productLimit;
  }

  public record Input(
      Purchase purchase, Context context, Preferences preferences, List<Long> productIds) {
    public Input {
      productIds = List.copyOf(productIds);
    }
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Input load(Long requestId) {
    var p =
        purchases
            .findById(requestId)
            .orElseThrow(() -> new IllegalStateException("Purchase request missing"));
    if (p.getStatus() != RecommendationStatus.RECOMMENDING)
      throw new IllegalStateException("Request is no longer recommending");
    var all = categories.findAll();
    var category =
        all.stream()
            .filter(c -> c.getId().equals(p.getPurposeCategoryId()))
            .findFirst()
            .orElseThrow();
    if (category.getParentId() == null)
      throw new IllegalStateException("Purchase category must be a child");
    var siblings =
        all.stream()
            .filter(
                c ->
                    category.getParentId().equals(c.getParentId())
                        && !category.getId().equals(c.getId()))
            .map(c -> c.getId())
            .collect(Collectors.toSet());
    var pickup = KoreanDateTime.toResponse(p.getPreferredPickupAt());
    var values =
        answers.decode(p.getAnswers()).stream()
            .collect(Collectors.toMap(a -> a.questionCode(), a -> a.value()));
    return new Input(
        new Purchase(
            p.getId(),
            p.getCustomerId(),
            p.getBudgetAmount().longValueExact(),
            pickup,
            p.getStatus().name()),
        new Context(
            category.getName(), p.getBudgetAmount().longValueExact(), pickup.toString(), values),
        new Preferences(category.getId(), siblings, Map.of()),
        products.findRecommendationProductIds(
            SaleStatus.ON_SALE, p.getBudgetAmount(), PageRequest.of(0, productLimit)));
  }
}
