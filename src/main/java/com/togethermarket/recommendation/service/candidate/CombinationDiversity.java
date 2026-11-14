package com.togethermarket.recommendation.service.candidate;

import java.util.Collection;
import java.util.List;

public final class CombinationDiversity {
  private CombinationDiversity() {}

  public static List<String> names(Collection<String> names) {
    return names.stream().map(StoreIdentity::normalize).sorted().toList();
  }
}
