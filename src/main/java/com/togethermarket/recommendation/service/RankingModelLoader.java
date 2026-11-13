package com.togethermarket.recommendation.service;

import static com.togethermarket.recommendation.service.RecommendationJson.array;
import static com.togethermarket.recommendation.service.RecommendationJson.integer;
import static com.togethermarket.recommendation.service.RecommendationJson.string;

public final class RankingModelLoader {
  private RankingModelLoader() {}

  public static RankingModel load(String json, String expectedApiModel) {
    var model = RecommendationJson.parse(json);
    if (integer(model.get("artifact_version")) != 1
        || !"trained".equals(model.get("mode"))
        || !RankingModel.FEATURE_VERSION.equals(model.get("feature_version"))
        || !RankingModel.FEATURE_NAMES.equals(array(model.get("feature_names")))
        || !RecommendationJson.promptVersion().equals(model.get("prompt_version"))
        || !expectedApiModel.equals(model.get("api_model"))
        || integer(model.get("training_scenarios")) < 1
        || integer(model.get("training_comparisons")) < 1) {
      throw new IllegalArgumentException(
          "Untrained model or incompatible model/prompt/feature version");
    }
    return new RankingModel(
        "trained",
        string(model.get("ranker_version")),
        array(model.get("weights")).stream().map(RecommendationJson::number).toList());
  }
}
