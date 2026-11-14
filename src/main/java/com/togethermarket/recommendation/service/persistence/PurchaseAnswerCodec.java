package com.togethermarket.recommendation.service.persistence;

import com.togethermarket.recommendation.dto.response.PurchaseRequestResponse.Answer;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Stored JSON is a structured array of server-labelled snapshots, not a JSON-encoded string. */
public final class PurchaseAnswerCodec {
  private final JsonMapper mapper = new JsonMapper();

  public String encode(List<Answer> answers) {
    validate(answers);
    return mapper.writeValueAsString(answers);
  }

  public List<Answer> decode(String json) {
    var answers = List.of(mapper.readValue(json, Answer[].class));
    validate(answers);
    return answers;
  }

  private void validate(List<Answer> answers) {
    var codes = new HashSet<String>();
    for (var answer : answers) {
      if (answer == null
          || answer.questionCode() == null
          || answer.questionCode().isBlank()
          || !codes.add(answer.questionCode())
          || answer.value() == null
          || answer.label() == null
          || answer.label().isBlank()) {
        throw new IllegalArgumentException("Invalid stored answer snapshot");
      }
    }
  }
}
