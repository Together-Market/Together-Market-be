package com.togethermarket.recommendation.service;

import static com.togethermarket.recommendation.service.RecommendationJson.*;

import com.togethermarket.recommendation.service.RecommendationInput.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Gemini generateContent adapter; uses the same rubric and JSON schema as training. */
public final class GeminiCandidateEvaluator implements CandidateEvaluator {
  public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
  private static final System.Logger LOG =
      System.getLogger(GeminiCandidateEvaluator.class.getName());
  private final String apiKey;
  private final String model;
  private final int maxAttempts;
  private final URI endpoint;
  private final HttpClient client;

  public GeminiCandidateEvaluator(String apiKey, String model) {
    this(apiKey, model, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  public GeminiCandidateEvaluator(String apiKey, String model, int maxAttempts) {
    this(
        apiKey,
        model,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        maxAttempts);
  }

  GeminiCandidateEvaluator(String apiKey, String model, HttpClient client) {
    this(apiKey, model, client, 2);
  }

  GeminiCandidateEvaluator(String apiKey, String model, HttpClient client, int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > 2)
      throw new IllegalArgumentException("Gemini attempts must be 1 or 2");
    this.maxAttempts = maxAttempts;
    if (apiKey == null || apiKey.isBlank())
      throw new IllegalArgumentException("GEMINI_API_KEY is required");
    if (model == null || !model.matches("gemini-[A-Za-z0-9._-]+"))
      throw new IllegalArgumentException("GEMINI_MODEL must be a Gemini model ID, not a URL");
    this.apiKey = apiKey;
    this.model = model;
    this.endpoint =
        URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/"
                + model
                + ":generateContent");
    this.client = java.util.Objects.requireNonNull(client);
  }

  Map<String, Object> requestBody(Context context, List<Candidate> candidates) {
    return Map.of(
        "systemInstruction",
            Map.of("parts", List.of(Map.of("text", resource("evaluation-prompt.txt")))),
        "contents",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "parts",
                    List.of(
                        Map.of(
                            "text",
                            stringify(Map.of("context", context, "candidates", candidates)))))),
        "generationConfig",
            Map.of(
                "candidateCount",
                1,
                "maxOutputTokens",
                4000,
                "responseMimeType",
                "application/json",
                "responseJsonSchema",
                parse(resource("evaluation-schema.json"))));
  }

  @Override
  public List<Evaluation> evaluate(Context context, List<Candidate> candidates) {
    if (candidates.isEmpty() || candidates.size() > 12)
      throw new IllegalArgumentException("AI input must contain 1..12 candidates");
    String body = stringify(requestBody(context, candidates));
    String callId = java.util.UUID.randomUUID().toString();
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(15))
              .header("x-goog-api-key", apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 200) {
          LOG.log(
              System.Logger.Level.INFO,
              "GEMINI_USAGE "
                  + stringify(
                      Map.of(
                          "call_id",
                          callId,
                          "model",
                          model,
                          "attempt",
                          attempt + 1,
                          "usage",
                          usage(response.body()))));
          return parseResponse(response.body());
        }
        if (attempt + 1 < maxAttempts && (status == 429 || status >= 500)) {
          Thread.sleep(1000);
          continue;
        }
        throw new IllegalStateException("Gemini HTTP " + status + "; response body omitted");
      } catch (IOException error) {
        if (attempt + 1 == maxAttempts)
          throw new IllegalStateException("Gemini connection or timeout failure");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Gemini request interrupted");
      }
    }
    throw new IllegalStateException("Gemini request failed");
  }

  /** Whitelisted numeric metadata only: never log prompts, keys or raw provider bodies. */
  static Map<String, Long> usage(String body) {
    var raw = parse(body).get("usageMetadata");
    if (!(raw instanceof Map<?, ?> values)) return Map.of();
    var result = new java.util.LinkedHashMap<String, Long>();
    for (String field :
        List.of(
            "promptTokenCount",
            "candidatesTokenCount",
            "thoughtsTokenCount",
            "totalTokenCount",
            "cachedContentTokenCount",
            "toolUsePromptTokenCount")) {
      Object value = values.get(field);
      if (value instanceof Number number && number.longValue() >= 0)
        result.put(field, number.longValue());
    }
    return Map.copyOf(result);
  }

  static List<Evaluation> parseResponse(String body) {
    var response = parse(body);
    var feedback = object(response.getOrDefault("promptFeedback", Map.of()));
    if (feedback.containsKey("blockReason"))
      throw new IllegalArgumentException("Gemini blocked the request");
    var candidates = array(response.get("candidates"));
    if (candidates.size() != 1)
      throw new IllegalArgumentException("Exactly one Gemini response required");
    var candidate = object(candidates.getFirst());
    if (!"STOP".equals(candidate.get("finishReason")))
      throw new IllegalArgumentException("Gemini response was not completed");
    for (var value : array(candidate.getOrDefault("safetyRatings", List.of()))) {
      if (Boolean.TRUE.equals(object(value).get("blocked")))
        throw new IllegalArgumentException("Gemini response was blocked");
    }
    var output = new StringBuilder();
    for (var value : array(object(candidate.get("content")).get("parts"))) {
      var part = object(value);
      if (Boolean.TRUE.equals(part.get("thought"))) continue;
      if (!part.containsKey("text"))
        throw new IllegalArgumentException("Gemini text response required");
      output.append(string(part.get("text")));
    }
    return EvaluationJson.parse(output.toString());
  }
}
