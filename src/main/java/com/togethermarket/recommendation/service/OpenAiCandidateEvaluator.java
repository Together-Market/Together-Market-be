package com.togethermarket.recommendation.service;

import static com.togethermarket.recommendation.service.RecommendationJson.array;
import static com.togethermarket.recommendation.service.RecommendationJson.integer;
import static com.togethermarket.recommendation.service.RecommendationJson.object;
import static com.togethermarket.recommendation.service.RecommendationJson.string;

import com.togethermarket.recommendation.service.RecommendationInput.Candidate;
import com.togethermarket.recommendation.service.RecommendationInput.Context;
import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Responses API adapter. Fails closed on refusals, incomplete output and malformed results. */
public final class OpenAiCandidateEvaluator implements CandidateEvaluator {
  private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
  private final String apiKey;
  private final String apiModel;
  private final HttpClient client;

  public OpenAiCandidateEvaluator(String apiKey, String apiModel) {
    if (apiKey == null || apiKey.isBlank() || apiModel == null || apiModel.isBlank()) {
      throw new IllegalArgumentException("OPENAI_API_KEY and OPENAI_MODEL are required");
    }
    this.apiKey = apiKey;
    this.apiModel = apiModel;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  Map<String, Object> requestBody(Context context, List<Candidate> candidates) {
    return Map.of(
        "model",
        apiModel,
        "store",
        false,
        "instructions",
        RecommendationJson.resource("evaluation-prompt.txt"),
        "input",
        RecommendationJson.stringify(Map.of("context", context, "candidates", candidates)),
        "max_output_tokens",
        4000,
        "text",
        Map.of(
            "format",
            Map.of(
                "type",
                "json_schema",
                "name",
                "recommendation_evaluation",
                "strict",
                true,
                "schema",
                RecommendationJson.parse(RecommendationJson.resource("evaluation-schema.json")))));
  }

  @Override
  public List<Evaluation> evaluate(Context context, List<Candidate> candidates) {
    if (candidates.isEmpty() || candidates.size() > 12) {
      throw new IllegalArgumentException("AI input must contain 1..12 candidates");
    }
    String body = RecommendationJson.stringify(requestBody(context, candidates));
    for (int attempt = 0; attempt < 2; attempt++) {
      var request =
          HttpRequest.newBuilder(ENDPOINT)
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 200) {
          return parseResponse(response.body());
        }
        if (attempt == 0 && (status == 429 || status >= 500)) {
          Thread.sleep(1000);
          continue;
        }
        throw new IllegalStateException("AI HTTP " + status + "; response body omitted");
      } catch (IOException error) {
        if (attempt == 1) {
          throw new IllegalStateException("AI connection or timeout failure");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("AI request interrupted");
      }
    }
    throw new IllegalStateException("AI request failed");
  }

  static List<Evaluation> parseResponse(String body) {
    var response = RecommendationJson.parse(body);
    if (!"completed".equals(response.get("status"))) {
      throw new IllegalArgumentException("AI response was not completed");
    }
    var output = new StringBuilder();
    for (Object item : array(response.get("output"))) {
      var message = object(item);
      if (!"message".equals(message.get("type"))) {
        continue;
      }
      for (Object content : array(message.get("content"))) {
        var block = object(content);
        if ("refusal".equals(block.get("type"))) {
          throw new IllegalArgumentException("AI refused the request");
        }
        if ("output_text".equals(block.get("type"))) {
          output.append(string(block.get("text")));
        }
      }
    }
    var parsed = RecommendationJson.parse(output.toString());
    if (!parsed.keySet().equals(Set.of("evaluations"))) {
      throw new IllegalArgumentException("Unexpected AI output fields");
    }
    var evaluations = new ArrayList<Evaluation>();
    for (Object value : array(parsed.get("evaluations"))) {
      var item = object(value);
      if (!item.keySet()
          .equals(
              Set.of(
                  "candidate_id",
                  "purpose_score",
                  "taste_score",
                  "recipient_score",
                  "complement_score",
                  "exclusion_status",
                  "evidence_product_ids",
                  "reason"))) {
        throw new IllegalArgumentException("Unexpected evaluation fields");
      }
      evaluations.add(
          new Evaluation(
              string(item.get("candidate_id")),
              integer(item.get("purpose_score")),
              integer(item.get("taste_score")),
              integer(item.get("recipient_score")),
              integer(item.get("complement_score")),
              string(item.get("exclusion_status")),
              array(item.get("evidence_product_ids")).stream()
                  .map(RecommendationJson::string)
                  .toList(),
              string(item.get("reason"))));
    }
    return List.copyOf(evaluations);
  }
}
