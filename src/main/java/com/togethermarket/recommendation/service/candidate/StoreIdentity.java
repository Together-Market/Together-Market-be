package com.togethermarket.recommendation.service.candidate;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative recommendation identity only; never merges or deletes source rows. */
public final class StoreIdentity {
  private static final Pattern ROAD = Pattern.compile("^(.+?(?:로|길)\\s*\\d+(?:-\\d+)?)");

  private StoreIdentity() {}

  public static String normalize(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("[시연]", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{P}]", "");
  }

  public static String key(Long id, String name, String address) {
    if (name == null || name.isBlank() || address == null || address.isBlank()) return "id:" + id;
    String canonical =
        Normalizer.normalize(address, Normalizer.Form.NFKC).replaceFirst("^경기\\s", "경기도 ").strip();
    var road = ROAD.matcher(canonical);
    // Same name in the same road-address building is conservatively treated as one merchant.
    // Different merchants in the same mall still have different keys.
    String building = road.find() ? road.group(1) : canonical;
    return normalize(name) + "|" + normalize(building);
  }
}
