package com.togethermarket.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** A checked, versioned catalog packaged in the executable jar; no machine-local paths. */
record CsvDemoBundle(Manifest manifest, Catalog catalog, Map<RowKey, Map<String, String>> rows) {
  record Manifest(
      int formatVersion,
      String createdAt,
      String sourceObservedAt,
      List<String> sourceUrls,
      int originalCsvRows,
      int stores,
      int productStores,
      int products,
      Map<String, Integer> statuses,
      Map<String, String> files) {}

  record Catalog(
      int formatVersion, String notice, List<Store> stores, List<SourceLink> sourceLinks) {}

  record Store(
      String storeKey,
      String name,
      String businessType,
      String address,
      String latitude,
      String longitude,
      String area,
      List<Product> products,
      List<Hour> hours) {}

  record Product(
      String name,
      String description,
      long priceAmount,
      int preparationMinutes,
      String saleStatus,
      List<Long> purposeCategoryIds) {}

  record Hour(
      int weekday,
      String hourType,
      boolean isClosed,
      String opensAt,
      String closesAt,
      int endDayOffset) {}

  record SourceLink(String file, int line, String storeKey) {}

  record RowKey(String file, int line) {}

  private static final Set<String> FILES =
      Set.of(
          "catalog.json",
          "raw/bojeong_candidates_sbiz.csv",
          "raw/bojeong_candidates_ypay.csv",
          "raw/dongbaek_candidates_sbiz.csv",
          "raw/dongbaek_candidates_ypay.csv");

  static CsvDemoBundle load() throws IOException {
    var mapper =
        JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    var manifest = mapper.readValue(resource("manifest.json"), Manifest.class);
    if (manifest.formatVersion() != 1 || !manifest.files().keySet().equals(FILES))
      throw new IllegalArgumentException("Unsupported CSV demonstration bundle");
    var contents = new HashMap<String, byte[]>();
    for (String file : FILES) {
      byte[] bytes = resource(file);
      verifyChecksum(bytes, manifest.files().get(file));
      contents.put(file, bytes);
    }
    var catalog = mapper.readValue(contents.get("catalog.json"), Catalog.class);
    var rows = new HashMap<RowKey, Map<String, String>>();
    for (String file : FILES) {
      if (!file.startsWith("raw/")) continue;
      var parsed = CsvRows.parse(new String(contents.get(file), StandardCharsets.UTF_8));
      for (int i = 0; i < parsed.size(); i++)
        rows.put(new RowKey(file.substring(4), i + 2), parsed.get(i));
    }
    var keys = new HashSet<String>();
    var statuses = new HashMap<String, Integer>();
    var productCount = 0;
    for (Store store : catalog.stores()) {
      if (!store.storeKey().matches("[0-9a-f]{64}")
          || !keys.add(store.storeKey())
          || !store.name().startsWith("[시연]"))
        throw new IllegalArgumentException("Invalid CSV store identity");
      var names = new HashSet<String>();
      for (Product product : store.products()) {
        if (!names.add(product.name())
            || !product.name().startsWith("[시연]")
            || !product.description().contains("시연용")
            || product.priceAmount() < 0
            || product.preparationMinutes() < 0
            || !Set.of("ON_SALE", "SOLD_OUT", "PAUSED").contains(product.saleStatus()))
          throw new IllegalArgumentException("Invalid CSV demonstration product");
        statuses.merge(product.saleStatus(), 1, Integer::sum);
        productCount++;
      }
    }
    var linkedRows = new HashSet<RowKey>();
    for (var link : catalog.sourceLinks()) {
      if (!linkedRows.add(new RowKey(link.file(), link.line()))
          || (link.storeKey() != null && !keys.contains(link.storeKey())))
        throw new IllegalArgumentException("Invalid CSV source link");
    }
    if (catalog.formatVersion() != 1
        || keys.size() != manifest.stores()
        || productCount != manifest.products()
        || !statuses.equals(manifest.statuses())
        || rows.size() != manifest.originalCsvRows()
        || !linkedRows.equals(rows.keySet()))
      throw new IllegalArgumentException("CSV demonstration counts or links do not match");
    return new CsvDemoBundle(manifest, catalog, Map.copyOf(rows));
  }

  static void verifyChecksum(byte[] bytes, String expected) {
    try {
      if (!HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
          .equals(expected))
        throw new IllegalArgumentException("CSV demonstration checksum mismatch");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (var input = new ClassPathResource("csv-demo/" + name).getInputStream()) {
      return input.readAllBytes();
    }
  }
}
