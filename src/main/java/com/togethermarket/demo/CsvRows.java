package com.togethermarket.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the packaged UTF-8 CSV sources, including escaped quotes and multiline fields. */
final class CsvRows {
  private CsvRows() {}

  static List<Map<String, String>> parse(String input) {
    String text = input.startsWith("\ufeff") ? input.substring(1) : input;
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && (ch == ',' || ch == '\n' || ch == '\r')) {
        row.add(cell.toString());
        cell.setLength(0);
        if (ch != ',') {
          rows.add(List.copyOf(row));
          row.clear();
          if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
      } else cell.append(ch);
    }
    if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
    if (!row.isEmpty() || !cell.isEmpty()) {
      row.add(cell.toString());
      rows.add(List.copyOf(row));
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("Empty CSV");
    var header = rows.removeFirst();
    if (header.stream().distinct().count() != header.size())
      throw new IllegalArgumentException("Duplicate CSV header");
    var result = new ArrayList<Map<String, String>>();
    for (var values : rows) {
      if (values.size() != header.size()) throw new IllegalArgumentException("CSV column mismatch");
      var mapped = new HashMap<String, String>();
      for (int i = 0; i < header.size(); i++) mapped.put(header.get(i), values.get(i));
      result.add(Map.copyOf(mapped));
    }
    return List.copyOf(result);
  }
}
