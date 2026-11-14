package com.togethermarket.recommendation.service;

import static com.togethermarket.recommendation.service.RecommendationJson.array;
import static com.togethermarket.recommendation.service.RecommendationJson.integer;
import static com.togethermarket.recommendation.service.RecommendationJson.string;

public final class RankingModelLoader {
  private RankingModelLoader() {}

  public static RankingModel load(String json, String expectedApiModel) {
    return load(json, "openai", expectedApiModel);
  }

  public static RankingModel load(String json, String expectedProvider, String expectedApiModel) {
    return read(json, expectedProvider, expectedApiModel, false);
  }

  public static RankingModel loadDemo(String json, String provider, String model) {
    return read(json, provider, model, true);
  }

  private static RankingModel read(
      String json, String expectedProvider, String expectedApiModel, boolean demo) {
    if (!java.util.Set.of("openai", "gemini").contains(expectedProvider))
      throw new IllegalArgumentException("Unsupported AI provider");
    var model = RecommendationJson.parse(json);
    if (integer(model.get("artifact_version")) != 1
        || !(demo ? "ai_synthetic_experiment" : "trained").equals(model.get("mode"))
        || !RankingModel.FEATURE_VERSION.equals(model.get("feature_version"))
        || !RankingModel.FEATURE_NAMES.equals(array(model.get("feature_names")))
        || !RecommendationJson.promptVersion().equals(model.get("prompt_version"))
        || !expectedProvider.equals(model.getOrDefault("api_provider", "openai"))
        || !expectedApiModel.equals(model.get("api_model"))
        || integer(model.get("training_scenarios")) < 1
        || integer(model.get("training_comparisons")) < 1) {
      throw new IllegalArgumentException(
          "Untrained model or incompatible model/prompt/feature version");
    }
    if (demo
        && (!Boolean.FALSE.equals(model.get("deployable"))
            || !"ai_synthetic".equals(model.get("label_source"))
            || !"live_api".equals(model.get("evaluation_source")))) {
      throw new IllegalArgumentException(
          "Demo requires the original live-API synthetic experiment metadata");
    }
    return new RankingModel(
        demo ? "demo" : "trained",
        string(model.get("ranker_version")),
        array(model.get("weights")).stream().map(RecommendationJson::number).toList());
  }
}
