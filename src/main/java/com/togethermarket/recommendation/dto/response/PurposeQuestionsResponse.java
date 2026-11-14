package com.togethermarket.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record PurposeQuestionsResponse(
    @JsonProperty("purpose_category_id") @JsonSerialize(using = ToStringSerializer.class)
        Long purposeCategoryId,
    List<Question> questions) {
  public PurposeQuestionsResponse {
    questions = List.copyOf(questions);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Question(
      int number,
      String code,
      String label,
      String type,
      boolean required,
      @JsonProperty("answer_field") String answerField,
      @JsonProperty("max_length") Integer maxLength,
      String unit,
      Long min,
      Long max) {}
}
