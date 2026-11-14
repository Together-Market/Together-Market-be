package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.dto.request.CreatePurchaseRequest;
import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse;
import com.togethermarket.recommendation.entity.*;
import com.togethermarket.recommendation.repository.PurchaseRequestRepository;
import com.togethermarket.recommendation.service.job.RecommendationAfterCommit;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** CUSTOMER identity is supplied by BE1's trusted session integration, never request JSON. */
public class JpaPurchaseCreationService {
  private final PurchaseRequestRepository purchases;
  private final UserRepository users;
  private final PurchaseConditionValidator validator;
  private final PurchaseAnswerCodec answers;
  private final RecommendationAfterCommit afterCommit;

  public JpaPurchaseCreationService(
      PurchaseRequestRepository purchases,
      UserRepository users,
      PurchaseConditionValidator validator,
      PurchaseAnswerCodec answers,
      RecommendationAfterCommit afterCommit) {
    this.purchases = purchases;
    this.users = users;
    this.validator = validator;
    this.answers = answers;
    this.afterCommit = afterCommit;
  }

  @Transactional
  public PurchaseRequestResponse create(CreatePurchaseRequest input, Long sessionCustomerId) {
    if (sessionCustomerId == null || sessionCustomerId <= 0)
      throw new PurchaseInputException("UNAUTHORIZED", "로그인이 필요합니다.", List.of());
    var user =
        users
            .findById(sessionCustomerId)
            .orElseThrow(
                () ->
                    new PurchaseInputException("UNAUTHORIZED", "로그인 사용자를 확인할 수 없습니다.", List.of()));
    if (user.getRole() != UserRole.CUSTOMER)
      throw new PurchaseInputException("FORBIDDEN", "고객만 구매요청을 생성할 수 있습니다.", List.of());
    var conditions = validator.validate(input);
    var pickup = KoreanDateTime.toDatabase(conditions.preferredPickupAt().toInstant());
    if (!KoreanDateTime.toInstant(pickup).isAfter(conditions.validatedAt())) {
      throw PurchaseInputException.invalid("preferred_pickup_at", "초 단위로 저장한 수령시각이 현재보다 미래여야 합니다.");
    }
    var row =
        purchases.saveAndFlush(
            new PurchaseRequest(
                sessionCustomerId,
                conditions.purposeCategoryId(),
                BigDecimal.valueOf(conditions.budgetAmount()),
                pickup,
                RecommendationStatus.RECOMMENDING,
                null,
                KoreanDateTime.toDatabase(conditions.validatedAt()),
                answers.encode(conditions.answers())));
    afterCommit.register(row.getId());
    return new PurchaseRequestResponse(
        row.getId(),
        row.getCustomerId(),
        row.getPurposeCategoryId(),
        row.getBudgetAmount().longValueExact(),
        KoreanDateTime.toResponse(row.getPreferredPickupAt()),
        conditions.answers(),
        row.getStatus().name(),
        KoreanDateTime.toResponse(row.getCreatedAt()),
        null);
  }
}
