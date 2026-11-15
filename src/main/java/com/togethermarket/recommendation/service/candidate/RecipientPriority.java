package com.togethermarket.recommendation.service.candidate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recipient policy is an ordering tier, never a weight that other preferences can outweigh. */
public record RecipientPriority(boolean specified, boolean parents) {
  public static RecipientPriority none() {
    return new RecipientPriority(false, false);
  }

  public static RecipientPriority from(Map<String, String> answers) {
    String recipient = answers.getOrDefault("recipient", "").strip().toLowerCase(Locale.ROOT);
    boolean parents =
        List.of("부모", "어머니", "아버지", "엄마", "아빠", "모친", "부친").stream().anyMatch(recipient::contains)
            || List.of("parent", "parents", "mother", "father", "mom", "dad").contains(recipient);
    return new RecipientPriority(!recipient.isBlank(), parents);
  }

  public int productKind(String name) {
    if (!parents) return 0;
    // Accessories and patterned stationery are not actual flowers or cakes.
    if (List.of("문구", "노트", "스티커", "토퍼", "캔들", "양초", "머그", "접시", "포장지").stream()
        .anyMatch(name::contains)) return 0;
    if (List.of("케이크", "케익", "케잌").stream().anyMatch(name::contains)) return 2;
    if (List.of("꽃다발", "꽃바구니", "플라워", "생화", "카네이션", "화분", "꽃 선물").stream().anyMatch(name::contains)
        || name.equals("꽃")) return 1;
    return 0;
  }

  public int pairTier(String first, String second) {
    return pairTier(productKind(first), productKind(second));
  }

  public static int pairTier(int a, int b) {
    // Flower + cake, then two suitable gifts, then one suitable gift, then other pairs.
    return Integer.bitCount(a | b) * 3 + (a == 0 ? 0 : 1) + (b == 0 ? 0 : 1);
  }
}
