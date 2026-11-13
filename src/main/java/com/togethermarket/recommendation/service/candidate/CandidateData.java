package com.togethermarket.recommendation.service.candidate;

import com.togethermarket.recommendation.service.purchase.PurchaseRules;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** BE3 read snapshots, not shared JPA entities or public response contracts. */
public final class CandidateData {
  private CandidateData() {}

  public record Purchase(
      Long id, Long customerId, long budgetAmount, OffsetDateTime pickupAt, String status) {
    public Purchase {
      positive(id);
      positive(customerId);
      Objects.requireNonNull(pickupAt);
      if (budgetAmount < PurchaseRules.MIN_BUDGET
          || budgetAmount > PurchaseRules.MAX_BUDGET
          || status == null
          || !Set.of("RECOMMENDING", "READY", "FAILED").contains(status)) {
        throw new IllegalArgumentException("Invalid purchase snapshot");
      }
    }
  }

  public record Product(
      Long id,
      Long storeId,
      String name,
      String description,
      String businessType,
      long priceAmount,
      Set<Long> purposeCategoryIds,
      Double latitude,
      Double longitude) {
    public Product {
      positive(id);
      positive(storeId);
      if (priceAmount <= 0
          || name == null
          || name.isBlank()
          || businessType == null
          || businessType.isBlank()) {
        throw new IllegalArgumentException("Invalid catalog product");
      }
      purposeCategoryIds = Set.copyOf(purposeCategoryIds);
      purposeCategoryIds.forEach(CandidateData::positive);
    }
  }

  public record SavedItem(Long id, Long productId, int quantity, long unitPriceSnapshot) {
    public SavedItem {
      positive(id);
      positive(productId);
      if (quantity < 1 || unitPriceSnapshot <= 0) {
        throw new IllegalArgumentException("Invalid saved recommendation item");
      }
    }
  }

  public record SavedCandidate(
      Long id, Long purchaseRequestId, int rank, String reason, List<SavedItem> items) {
    public SavedCandidate {
      positive(id);
      positive(purchaseRequestId);
      if (rank < 1 || reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Invalid saved recommendation candidate");
      }
      items = List.copyOf(items);
      if (items.size() != 2
          || items.get(0).id().equals(items.get(1).id())
          || items.get(0).productId().equals(items.get(1).productId())) {
        throw new IllegalArgumentException("Exactly two distinct recommendation items required");
      }
    }

    public List<Long> productPair() {
      return items.stream().map(SavedItem::productId).sorted().toList();
    }
  }

  /** BE1 adapter translates its reservation enum into failed; BE3 does not redefine it. */
  public record Reservation(
      Long id, Long customerId, Long purchaseRequestId, Long selectedCandidateId, boolean failed) {
    public Reservation {
      positive(id);
      positive(customerId);
      positive(purchaseRequestId);
      positive(selectedCandidateId);
    }
  }

  /** Internal reason codes; public JSON field names remain an integration decision. */
  public record UnavailableReason(Long productId, String code) {
    public UnavailableReason {
      if (productId != null) positive(productId);
      if (code == null || code.isBlank())
        throw new IllegalArgumentException("Reason code required");
    }
  }

  public record CurrentItem(SavedItem saved, Product product) {
    public CurrentItem {
      Objects.requireNonNull(saved);
      Objects.requireNonNull(product);
      if (!saved.productId().equals(product.id()))
        throw new IllegalArgumentException("Product ID mismatch");
    }

    public boolean priceChanged() {
      return saved.unitPriceSnapshot() != product.priceAmount();
    }
  }

  public record CheckedCandidate(
      SavedCandidate saved,
      List<CurrentItem> items,
      long totalAmount,
      List<UnavailableReason> reasons) {
    public CheckedCandidate {
      Objects.requireNonNull(saved);
      items = List.copyOf(items);
      reasons = List.copyOf(reasons);
    }

    public boolean available() {
      return reasons.isEmpty();
    }

    public boolean priceChanged() {
      return items.stream().anyMatch(CurrentItem::priceChanged);
    }
  }

  static void positive(Long id) {
    if (id == null || id <= 0)
      throw new IllegalArgumentException("Positive internal Long ID required");
  }
}
