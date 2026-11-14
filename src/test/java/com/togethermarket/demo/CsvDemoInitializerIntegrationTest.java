package com.togethermarket.demo;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CSV_DEMO_TEST_URL", matches = ".+")
@ActiveProfiles("local")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "demo.catalog.enabled=true",
      "spring.datasource.url=${CSV_DEMO_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD}",
      "reservation.approval-window=PT30M",
      "reservation.expiry-scheduler.enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CsvDemoInitializerIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  @Test
  void startupSeedsOnceAndRepeatedConcurrentAndFailedImportsPreserveData() throws Exception {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores", Integer.class)).isEqualTo(2224);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1899);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_store_records", Integer.class))
        .isEqualTo(3265);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE sale_status='ON_SALE'", Integer.class))
        .isEqualTo(1826);
    var bundle = CsvDemoBundle.load();
    var seeder = new CsvDemoSeeder(dataSource);
    Long pid = jdbc.queryForObject("SELECT MIN(id) FROM products", Long.class);
    jdbc.update("UPDATE products SET price_amount=12345,sale_status='PAUSED' WHERE id=?", pid);
    assertThat(seeder.seed(bundle)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT price_amount FROM products WHERE id=?", Long.class, pid))
        .isEqualTo(12345L);
    assertThat(
            jdbc.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, pid))
        .isEqualTo("PAUSED");

    var original = bundle.catalog().stores().getFirst();
    var concurrent = subset(bundle, copy(original, randomKey(), "[시연] 동시 입력 확인"));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(() -> seeder.seed(concurrent));
      var second = pool.submit(() -> seeder.seed(concurrent));
      var results = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
      assertThat(results.stream().filter(Map::isEmpty).count()).isEqualTo(1);
      assertThat(results.stream().mapToInt(r -> r.getOrDefault("stores", 0)).sum()).isEqualTo(1);
    }
    int users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    int stores = jdbc.queryForObject("SELECT COUNT(*) FROM stores", Integer.class);
    var failure =
        subset(
            bundle,
            copy(original, randomKey(), "[시연] 롤백 확인"),
            copy(original, original.storeKey(), "[시연] 기존 이름 충돌"));
    assertThatThrownBy(() -> seeder.seed(failure)).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(users);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores", Integer.class)).isEqualTo(stores);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_requests", Integer.class))
        .isZero();
  }

  private static String randomKey() {
    return UUID.randomUUID().toString().replace("-", "").repeat(2);
  }

  private static CsvDemoBundle.Store copy(CsvDemoBundle.Store s, String key, String name) {
    return new CsvDemoBundle.Store(
        key,
        name,
        s.businessType(),
        s.address(),
        s.latitude(),
        s.longitude(),
        s.area(),
        s.products(),
        s.hours());
  }

  private static CsvDemoBundle subset(CsvDemoBundle original, CsvDemoBundle.Store... stores) {
    return new CsvDemoBundle(
        original.manifest(),
        new CsvDemoBundle.Catalog(1, original.catalog().notice(), List.of(stores), List.of()),
        Map.of());
  }
}
