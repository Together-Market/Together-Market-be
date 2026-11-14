package com.togethermarket.recommendation.service.purchase;

import com.togethermarket.recommendation.dto.response.PurposeQuestionsResponse;
import com.togethermarket.recommendation.dto.response.PurposeQuestionsResponse.Question;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One definition drives both PUR-02 and PUR-03 validation. Labels must be supplied explicitly. */
public final class PurposeQuestionService {
  private final PurposeCategoryService categories;
  private final Map<Long, String> preferenceLabels;

  public PurposeQuestionService(
      PurposeCategoryService categories, Map<Long, String> preferenceLabels) {
    this.categories = Objects.requireNonNull(categories);
    this.preferenceLabels = Map.copyOf(preferenceLabels);
    if (this.preferenceLabels.entrySet().stream()
        .anyMatch(entry -> entry.getKey() <= 0 || entry.getValue().isBlank())) {
      throw new IllegalArgumentException(
          "Question labels must have positive category IDs and nonempty text");
    }
  }

  public PurposeQuestionsResponse questions(String categoryId) {
    return definition(PurchaseRules.parseId(categoryId, "category_id")).response();
  }

  public Definition definition(Long categoryId) {
    var category = categories.requireActiveChild(categoryId);
    String preference = preferenceLabels.get(categoryId);
    if (preference == null) {
      throw new IllegalStateException(
          "Category-specific question is not configured: " + categoryId);
    }
    return new Definition(
        category,
        new PurposeQuestionsResponse(
            categoryId,
            List.of(
                text(1, "style", "어떤 스타일을 좋아하시나요?", true),
                text(2, "excluded_products", "추천에서 피하고 싶은 상품이 있나요?", false),
                text(3, "recipient", "누구를 위한 상품인가요?", true),
                new Question(
                    4,
                    "budget",
                    "예산은 얼마인가요?",
                    "NUMBER",
                    true,
                    "budget_amount",
                    null,
                    "KRW",
                    PurchaseRules.MIN_BUDGET,
                    PurchaseRules.MAX_BUDGET),
                text(5, "category_preference", preference, true))));
  }

  public record Definition(
      PurposeCategorySource.Category category, PurposeQuestionsResponse response) {}

  private static Question text(int number, String code, String label, boolean required) {
    return new Question(
        number,
        code,
        label,
        "TEXT",
        required,
        "answers",
        PurchaseRules.MAX_ANSWER_LENGTH,
        null,
        null,
        null);
  }
}
