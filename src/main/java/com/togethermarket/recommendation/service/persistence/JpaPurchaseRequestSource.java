package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.entity.PurchaseRequest;
import com.togethermarket.recommendation.repository.PurchaseRequestRepository;
import com.togethermarket.recommendation.service.purchase.PurchaseRequestSource;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class JpaPurchaseRequestSource implements PurchaseRequestSource {
  private final PurchaseRequestRepository repository;
  private final PurchaseAnswerCodec answers;

  public JpaPurchaseRequestSource(
      PurchaseRequestRepository repository, PurchaseAnswerCodec answers) {
    this.repository = repository;
    this.answers = answers;
  }

  @Override
  public Optional<Snapshot> findById(Long requestId) {
    return repository.findById(requestId).map(this::snapshot);
  }

  public Snapshot snapshot(PurchaseRequest request) {
    return new Snapshot(
        request.getId(),
        request.getCustomerId(),
        request.getPurposeCategoryId(),
        request.getBudgetAmount().longValueExact(),
        KoreanDateTime.toResponse(request.getPreferredPickupAt()),
        answers.decode(request.getAnswers()),
        request.getStatus().name(),
        KoreanDateTime.toResponse(request.getCreatedAt()),
        request.getFailureCode());
  }
}
