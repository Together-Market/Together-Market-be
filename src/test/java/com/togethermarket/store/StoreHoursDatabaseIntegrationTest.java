package com.togethermarket.store;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.product.service.StoreScheduleEvaluator;
import com.togethermarket.store.dto.request.StoreHoursRequest;
import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.*;
import com.togethermarket.store.service.OwnerStoreHoursService;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
class StoreHoursDatabaseIntegrationTest {
  @Autowired OwnerStoreHoursService service;
  @Autowired StoreRepository stores;
  @Autowired StoreHourRepository hours;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired StoreScheduleEvaluator evaluator;

  private Store setup() {
    var owner =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "사장", UserRole.OWNER));
    return stores.saveAndFlush(new Store(owner.getId(), "꽃집", "꽃집", "용인", null, null, "010"));
  }

  private StoreHoursRequest request(int day) {
    return StoreHoursRequest.parse(
        Map.of(
            "items",
            List.of(
                new HashMap<String, Object>() {
                  {
                    put("weekday", day);
                    put("target_date", null);
                    put("hour_type", "BUSINESS");
                    put("is_closed", false);
                    put("opens_at", "09:00:00");
                    put("closes_at", "18:00:00");
                    put("end_day_offset", 0);
                  }
                })));
  }

  @Test
  void replaceSameUniqueKeyAndClearAll() {
    var s = setup();
    service.replace(s.getOwnerId(), s.getId(), request(1));
    service.replace(s.getOwnerId(), s.getId(), request(1));
    assertEquals(1, service.get(s.getOwnerId(), s.getId()).items().size());
    assertTrue(
        evaluator.allows(
            hours.findByStoreId(s.getId()),
            HourType.BUSINESS,
            LocalDate.of(2026, 11, 16).atTime(12, 0)));
    service.replace(s.getOwnerId(), s.getId(), StoreHoursRequest.parse(Map.of("items", List.of())));
    assertTrue(hours.findByStoreId(s.getId()).isEmpty());
  }

  @Test
  void insertionFailureRollsBackDeletion() {
    var s = setup();
    service.replace(s.getOwnerId(), s.getId(), request(1));
    jdbc.execute(
        "CREATE TRIGGER test_reject_hour_insert BEFORE INSERT ON store_hours FOR EACH ROW BEGIN IF NEW.store_id = "
            + s.getId()
            + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected test failure'; END IF; END");
    try {
      assertThrows(
          RuntimeException.class, () -> service.replace(s.getOwnerId(), s.getId(), request(2)));
      assertEquals((short) 1, hours.findByStoreId(s.getId()).getFirst().getWeekday());
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_hour_insert");
    }
  }

  @Test
  void concurrentReplacementsDoNotMixSchedules() throws Exception {
    var s = setup();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return service.replace(s.getOwnerId(), s.getId(), request(1));
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return service.replace(s.getOwnerId(), s.getId(), request(2));
              });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      var saved = hours.findByStoreId(s.getId());
      assertEquals(1, saved.size());
      assertTrue(saved.getFirst().getWeekday() == 1 || saved.getFirst().getWeekday() == 2);
    }
  }
}
