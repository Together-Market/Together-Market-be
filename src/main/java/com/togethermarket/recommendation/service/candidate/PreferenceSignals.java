package com.togethermarket.recommendation.service.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Small deterministic retrieval vocabulary; semantic evaluation still belongs to Gemini. */
public record PreferenceSignals(long desired, List<String> excluded, boolean requireAll) {
  private static final List<List<String>> FAMILIES =
      List.of(
          List.of("꽃", "꽃다발", "꽃바구니", "화분", "화초", "플라워", "식물"),
          List.of("디저트", "케이크", "쿠키", "빵", "마카롱", "베이커리", "도넛", "떡"),
          List.of("음료", "커피", "라떼", "아메리카노", "티", "차", "주스", "에이드"),
          List.of("도서", "독서", "책"),
          List.of("문구", "편지", "노트", "필기구", "펜"),
          List.of("양말", "의류", "패브릭", "옷"),
          List.of("뷰티", "셀프케어", "파우치", "화장품"),
          List.of("식사", "포장", "도시락", "음식"),
          List.of("과일"),
          List.of("간식", "다과", "식품"),
          List.of("공예", "꾸미기", "키트"),
          List.of("반려동물", "산책"),
          List.of("머그컵", "식탁", "생활소품"),
          List.of("운동", "스포츠", "야외활동", "피크닉"),
          List.of("뜨개질", "뜨개", "뜨개실", "털실", "원사", "코바늘", "대바늘"));
  private static final Pattern NEGATIVE = Pattern.compile("말고|빼고|제외|싫|원하지|안좋아");
  private static final Pattern ALTERNATIVE = Pattern.compile("또는|혹은|아무거나|중하나|or|/");

  public PreferenceSignals {
    excluded = List.copyOf(excluded);
  }

  public static PreferenceSignals none() {
    return new PreferenceSignals(0, List.of(), false);
  }

  public static PreferenceSignals from(Map<String, String> answers) {
    String text = answers.getOrDefault("category_preference", "");
    var negative = new ArrayList<String>();
    StringBuilder positive = new StringBuilder();
    for (String clause : text.split("[,.;\\n]|하지만|좋고|좋지만|대신")) {
      var match = NEGATIVE.matcher(clause.replace(" ", ""));
      if (match.find()) {
        String compact = clause.replace(" ", "");
        negative.add(compact.substring(0, match.start()));
        positive.append(" ").append(compact.substring(match.end()));
      } else positive.append(" ").append(clause);
    }
    negative.add(answers.getOrDefault("excluded_products", ""));
    var excluded = new ArrayList<String>();
    for (var family : FAMILIES) {
      if (negative.stream().anyMatch(s -> mentions(s, family.getFirst()))) excluded.addAll(family);
      else
        for (String term : family) {
          if (negative.stream().anyMatch(s -> mentions(s, term))) excluded.add(term);
        }
    }
    long desired = mask(positive.toString());
    // A conjunction of two named product families means the pair should cover both.
    boolean all =
        Long.bitCount(desired) > 1
            && !ALTERNATIVE.matcher(positive.toString().replace(" ", "")).find();
    return new PreferenceSignals(desired, excluded, all);
  }

  private static boolean mentions(String text, String term) {
    String value = text.toLowerCase(java.util.Locale.ROOT);
    // Avoid one-character substring false positives such as "차" in "차분한" or "책" in "산책".
    if (term.length() == 1)
      return Pattern.compile("(^|[\\s·,+/])" + term + "(?=$|[\\s·,+/]|와|과|을|를|은|는|이랑|랑|두|한|선물)")
          .matcher(value)
          .find();
    return value.contains(term);
  }

  public static long mask(String text) {
    long result = 0;
    for (int i = 0; i < FAMILIES.size(); i++) {
      for (String term : FAMILIES.get(i))
        if (mentions(text, term)) {
          result |= 1L << i;
          break;
        }
    }
    return result;
  }

  public long matches(String productName) {
    return mask(productName) & desired;
  }

  public boolean excludes(String productName) {
    return excluded.stream().anyMatch(t -> mentions(productName, t));
  }

  public boolean accepts(long a, long b) {
    return !requireAll || ((a | b) & desired) == desired;
  }

  public int coverage(long a, long b) {
    return Long.bitCount(a | b) * 3 + (a == 0 ? 0 : 1) + (b == 0 ? 0 : 1);
  }
}
