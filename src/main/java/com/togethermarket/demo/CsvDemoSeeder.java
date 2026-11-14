package com.togethermarket.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Inserts only missing demonstration rows, using one connection and one transaction. */
final class CsvDemoSeeder {
  private final DataSource dataSource;

  CsvDemoSeeder(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  Map<String, Integer> seed(CsvDemoBundle bundle) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
      String lock = database + ":csv_demo_seed_v1";
      if (database == null || lock.length() > 64)
        throw new IllegalStateException("A valid application database must be selected");
      if (!Integer.valueOf(1)
          .equals(jdbc.queryForObject("SELECT GET_LOCK(?,30)", Integer.class, lock)))
        throw new IllegalStateException("CSV demonstration import lock unavailable");
      boolean previousAutoCommit = connection.getAutoCommit();
      try {
        connection.setAutoCommit(false);
        var changes = populate(connection, jdbc, bundle);
        connection.commit();
        return Map.copyOf(changes);
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      } finally {
        try {
          jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
        } finally {
          connection.setAutoCommit(previousAutoCommit);
        }
      }
    }
  }

  private Map<String, Integer> populate(
      Connection connection, JdbcTemplate jdbc, CsvDemoBundle bundle) throws Exception {
    var categories =
        new HashSet<>(jdbc.queryForList("SELECT id FROM purpose_categories", Long.class));
    for (var store : bundle.catalog().stores()) {
      for (var product : store.products()) {
        if (!categories.containsAll(product.purposeCategoryIds()))
          throw new IllegalStateException("CSV purpose categories are missing");
      }
    }
    byte[] randomPassword = new byte[32];
    new java.security.SecureRandom().nextBytes(randomPassword);
    String lockedHash =
        new BCryptPasswordEncoder().encode(HexFormat.of().formatHex(randomPassword));
    var changes = new HashMap<String, Integer>();
    var storeIds = new HashMap<String, Long>();
    for (var store : bundle.catalog().stores()) {
      String email = "csv-demo-" + store.storeKey() + "@demo.together.invalid";
      var owners = jdbc.queryForList("SELECT id,role FROM users WHERE email=?", email);
      long owner;
      if (owners.isEmpty()) {
        owner =
            insert(
                connection,
                "INSERT INTO users(email,password_hash,name,role) VALUES(?,?,'CSV 시연 점주','OWNER')",
                email,
                lockedHash);
        increment(changes, "owners");
      } else {
        if (owners.size() != 1 || !"OWNER".equals(owners.getFirst().get("role")))
          throw new IllegalStateException("CSV owner identity conflict");
        owner = id(owners.getFirst());
      }
      var stores = jdbc.queryForList("SELECT id,name,address FROM stores WHERE owner_id=?", owner);
      long sid;
      if (stores.isEmpty()) {
        sid =
            insert(
                connection,
                "INSERT INTO stores(owner_id,name,business_type,address,latitude,longitude,phone) VALUES(?,?,?,?,?,?,'')",
                owner,
                store.name(),
                store.businessType(),
                store.address(),
                store.latitude(),
                store.longitude());
        increment(changes, "stores");
      } else {
        if (stores.size() != 1
            || !store.name().equals(stores.getFirst().get("name"))
            || !store.address().equals(stores.getFirst().get("address")))
          throw new IllegalStateException("CSV store identity changed; import was rolled back");
        sid = id(stores.getFirst());
      }
      storeIds.put(store.storeKey(), sid);
      var products = new HashMap<String, Long>();
      for (var p : jdbc.queryForList("SELECT id,name FROM products WHERE store_id=?", sid)) {
        if (products.put((String) p.get("name"), id(p)) != null)
          throw new IllegalStateException("CSV product identity is ambiguous");
      }
      for (var product : store.products()) {
        Long pid = products.get(product.name());
        if (pid == null) {
          pid =
              insert(
                  connection,
                  "INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES(?,?,?,?,?,?)",
                  sid,
                  product.name(),
                  product.description(),
                  product.priceAmount(),
                  product.preparationMinutes(),
                  product.saleStatus());
          increment(changes, "products");
        }
        var tags =
            new HashSet<>(
                jdbc.queryForList(
                    "SELECT purpose_category_id FROM product_purpose_tags WHERE product_id=?",
                    Long.class,
                    pid));
        for (long tag : product.purposeCategoryIds()) {
          if (!tags.contains(tag)) {
            jdbc.update(
                "INSERT INTO product_purpose_tags(product_id,purpose_category_id) VALUES(?,?)",
                pid,
                tag);
            increment(changes, "tags");
          }
        }
      }
      var hours = new HashSet<String>();
      for (var h :
          jdbc.queryForList(
              "SELECT weekday,hour_type FROM store_hours WHERE store_id=? AND weekday IS NOT NULL",
              sid)) hours.add(h.get("weekday") + ":" + h.get("hour_type"));
      for (var h : store.hours()) {
        if (!hours.contains(h.weekday() + ":" + h.hourType())) {
          jdbc.update(
              "INSERT INTO store_hours(store_id,weekday,hour_type,is_closed,opens_at,closes_at,end_day_offset) VALUES(?,?,?,?,?,?,?)",
              sid,
              h.weekday(),
              h.hourType(),
              h.isClosed(),
              h.opensAt(),
              h.closesAt(),
              h.endDayOffset());
          increment(changes, "hours");
        }
      }
    }
    var observed =
        OffsetDateTime.parse(bundle.manifest().sourceObservedAt())
            .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .toLocalDateTime();
    for (var link : bundle.catalog().sourceLinks()) {
      var row = bundle.rows().get(new CsvDemoBundle.RowKey(link.file(), link.line()));
      String area = link.file().startsWith("bojeong_") ? "BOJEONG" : "DONGBAEK";
      boolean sbiz = link.file().endsWith("_sbiz.csv");
      String source = "CSV_DEMO_V1_" + area + (sbiz ? "_SBIZ" : "_YPAY");
      String external = row.get(sbiz ? "external_id" : "source_record_key");
      String sourceRow = sbiz ? Integer.toString(link.line() - 1) : row.get("source_row_number");
      Long matched = link.storeKey() == null ? null : storeIds.get(link.storeKey());
      var existing =
          jdbc.queryForList(
              "SELECT matched_store_id FROM public_store_records WHERE source=? AND external_id=? AND source_row_number=?",
              source,
              external,
              sourceRow);
      if (existing.size() > 1
          || (!existing.isEmpty()
              && !java.util.Objects.equals(
                  matched,
                  existing.getFirst().get("matched_store_id") == null
                      ? null
                      : ((Number) existing.getFirst().get("matched_store_id")).longValue())))
        throw new IllegalStateException("CSV public source mapping conflict");
      if (existing.isEmpty()) {
        jdbc.update(
            "INSERT INTO public_store_records(source,external_id,source_row_number,name,branch_name,business_type,address,latitude,longitude,last_seen_at,matched_store_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            source,
            external,
            sourceRow,
            row.get("name"),
            clean(row.get("branch_name")),
            row.get("business_type"),
            row.get("address"),
            clean(row.get("latitude")),
            clean(row.get("longitude")),
            observed,
            matched);
        increment(changes, "public_records");
      }
    }
    return changes;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() || value.equals("NULL") ? null : value;
  }

  private static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  private static void increment(Map<String, Integer> changes, String name) {
    changes.merge(name, 1, Integer::sum);
  }

  private static long insert(Connection connection, String sql, Object... values) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
      try (var keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new IllegalStateException("Missing generated demonstration ID");
        return keys.getLong(1);
      }
    }
  }
}
