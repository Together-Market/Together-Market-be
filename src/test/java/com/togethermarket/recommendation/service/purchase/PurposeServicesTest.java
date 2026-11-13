package com.togethermarket.recommendation.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togethermarket.recommendation.service.purchase.PurposeCategorySource.Category;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PurposeServicesTest {
  @Test
  void referenceContainsAllCategoriesAndExplicitlyDraftQuestionLabels() {
    var reference = PurposeReferenceData.loadDraft();
    var service = new PurposeCategoryService(reference::categories);
    var tree = service.categories();
    assertThat(reference.status()).contains("DRAFT");
    assertThat(tree.items()).hasSize(3);
    assertThat(tree.items().stream().flatMap(r -> r.children().stream()).toList()).hasSize(22);
    assertThat(reference.categoryPreferenceLabels().keySet())
        .containsExactlyInAnyOrderElementsOf(
            tree.items().stream().flatMap(r -> r.children().stream()).map(c -> c.id()).toList());
    assertThat(tree.items()).extracting(r -> r.id()).containsExactly(1L, 2L, 3L);
  }

  @Test
  void filtersInactiveParentsAndChildrenAndBreaksOrderTiesById() {
    var source =
        List.of(
            new Category(2L, null, "숨긴 부모", 1, false),
            new Category(1L, null, "활성 부모", 2, true),
            new Category(13L, 1L, "세 번째", 2, true),
            new Category(12L, 1L, "두 번째", 1, true),
            new Category(11L, 1L, "첫 번째", 1, true),
            new Category(14L, 1L, "숨긴 자식", 0, false),
            new Category(21L, 2L, "부모가 숨겨짐", 1, true));
    var service = new PurposeCategoryService(() -> source);
    assertThat(service.categories().items()).hasSize(1);
    assertThat(service.categories().items().getFirst().children())
        .extracting(c -> c.id())
        .containsExactly(11L, 12L, 13L);
    for (long id : new long[] {1L, 14L, 21L}) {
      assertThatThrownBy(() -> service.requireActiveChild(id))
          .isInstanceOfSatisfying(
              PurchaseInputException.class,
              e -> assertThat(e.code()).isEqualTo("INVALID_PURPOSE_CATEGORY"));
    }
    assertThatThrownBy(() -> service.requireActiveChild(999L))
        .isInstanceOfSatisfying(
            PurchaseInputException.class,
            e -> assertThat(e.code()).isEqualTo("PURPOSE_CATEGORY_NOT_FOUND"));
  }

  @Test
  void incompleteOrDuplicateTreesAreAdapterErrors() {
    Category child = new Category(11L, 1L, "생일", 1, true);
    assertThatThrownBy(() -> new PurposeCategoryService(() -> List.of(child)).categories())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PurposeCategoryService(() -> List.of(child, child)).categories())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void questionSerializationKeepsStringIdsAndTypeSpecificFields() {
    var reference = PurposeReferenceData.loadDraft();
    var categories = new PurposeCategoryService(reference::categories);
    var service = new PurposeQuestionService(categories, Map.of(11L, "서버에서 검토한 질문"));
    var response = service.questions("11");
    assertThat(response.questions())
        .extracting(q -> q.code())
        .containsExactly(
            "style", "excluded_products", "recipient", "budget", "category_preference");
    JsonMapper mapper = new JsonMapper();
    Map<?, ?> data = mapper.readValue(mapper.writeValueAsString(response), Map.class);
    assertThat(data.get("purpose_category_id")).isEqualTo("11");
    List<?> questions = (List<?>) data.get("questions");
    Map<?, ?> text = (Map<?, ?>) questions.getFirst();
    assertThat(text.containsKey("max_length")).isTrue();
    assertThat(text.containsKey("min")).isFalse();
    assertThat(text.containsKey("unit")).isFalse();
    Map<?, ?> budget = (Map<?, ?>) questions.get(3);
    assertThat(budget.get("answer_field")).isEqualTo("budget_amount");
    assertThat(budget.get("max")).isEqualTo(999_999_999_999L);
    assertThat(budget.containsKey("max_length")).isFalse();
    Map<?, ?> tree =
        mapper.readValue(mapper.writeValueAsString(categories.categories()), Map.class);
    Map<?, ?> root = (Map<?, ?>) ((List<?>) tree.get("items")).getFirst();
    assertThat(root.get("id")).isEqualTo("1");
    assertThat(((Map<?, ?>) ((List<?>) root.get("children")).getFirst()).get("parent_id"))
        .isEqualTo("1");
  }

  @Test
  void missingCategorySpecificLabelNeverSilentlyUsesAnotherQuestion() {
    var reference = PurposeReferenceData.loadDraft();
    var questions =
        new PurposeQuestionService(new PurposeCategoryService(reference::categories), Map.of());
    assertThatThrownBy(() -> questions.questions("11")).isInstanceOf(IllegalStateException.class);
  }
}
