package com.togethermarket.recommendation.service.candidate;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Birthday bundles contain one verified whole cake and one complementary product. */
public record GiftComposition(boolean birthday) {
  private static final Pattern SIZE = Pattern.compile("[1-9]호");
  private static final Pattern NON_WHOLE_DESCRIPTION =
      Pattern.compile(
          "(?:(?:조각|슬라이스|미니|도시락|컵|팬|롤)케이크|미니사이즈|mini(?:ature)?cake|cupcake|pancake|rollcake|sliced?cake)(?!(?:가|이|는|은)?(?:아니|아닌|아닙|아님))");
  private static final Pattern WHOLE_CAKE_DENIAL =
      Pattern.compile(
          "(?:홀케이크|통케이크|wholecake)(?:가|이|는|은)?(?:아니|아닌|아닙|아님|미포함|불포함|제외)|not(?:a)?wholecake");

  public static GiftComposition none() {
    return new GiftComposition(false);
  }

  public static GiftComposition from(String purpose) {
    String value = compact(purpose);
    return new GiftComposition(
        value.contains("생일") || value.contains("생신") || value.contains("birthday"));
  }

  public static boolean wholeCake(String name, String description) {
    String title = compact(name);
    if (List.of(
            "조각",
            "슬라이스",
            "미니",
            "도시락",
            "컵케이크",
            "팬케이크",
            "롤케이크",
            "토퍼",
            "캔들",
            "양초",
            "스티커",
            "모형",
            "비누",
            "케이크틀",
            "케이크상자",
            "케이크박스",
            "포장박스",
            "포장상자",
            "포장지",
            "cupcake",
            "pancake",
            "rollcake",
            "minicake")
        .stream()
        .anyMatch(title::contains)) return false;
    if (!List.of("케이크", "cake").stream().anyMatch(title::contains)) return false;
    String details = title + " " + compact(description);
    // Explicit contradictory descriptions must not become positive evidence for a whole cake.
    if (NON_WHOLE_DESCRIPTION.matcher(details).find() || WHOLE_CAKE_DENIAL.matcher(details).find())
      return false;
    return List.of("홀케이크", "통케이크", "wholecake").stream().anyMatch(details::contains)
        || SIZE.matcher(details).find();
  }

  public boolean accepts(boolean firstWholeCake, boolean secondWholeCake) {
    return !birthday || firstWholeCake != secondWholeCake;
  }

  public double budgetFit(long total, long budget) {
    return birthday ? (double) total / budget : 0;
  }

  private static String compact(String value) {
    return value == null
        ? ""
        : value
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT)
            .replace("케익", "케이크")
            .replace("케잌", "케이크");
  }
}
