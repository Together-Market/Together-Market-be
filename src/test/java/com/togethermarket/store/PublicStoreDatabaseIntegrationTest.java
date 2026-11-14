package com.togethermarket.store;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.*;
import com.togethermarket.store.service.publicdata.*;
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
class PublicStoreDatabaseIntegrationTest {
  @Autowired PublicStoreImportService importer;
  @Autowired PublicStoreMatchService matches;
  @Autowired JdbcTemplate jdbc;
  @Autowired PublicStoreRecordRepository records;
  @Autowired StoreRepository stores;
  @Autowired UserRepository users;
  private final LocalDateTime seen = LocalDateTime.of(2026, 11, 13, 12, 0);

  private PublicStoreData row() {
    return new PublicStoreData(
        "YPAY", null, "12", "가게" + UUID.randomUUID(), null, "카페", "경기 용인시 예시로 1", null, null);
  }

  private Long id(PublicStoreData r) {
    return jdbc.queryForObject(
        "SELECT id FROM public_store_records WHERE source=? AND source_record_key=?",
        Long.class,
        r.source(),
        r.key());
  }

  private Store store(PublicStoreData r, String address) {
    var user =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "사장", UserRole.OWNER));
    return stores.saveAndFlush(new Store(user.getId(), r.name(), "카페", address, null, null, "010"));
  }

  @Test
  void repeatImportPreservesMatchAndOwnerData() {
    var r = row();
    importer.savePage(List.of(r), seen);
    var s = store(r, r.address().replace("경기 ", "경기도 "));
    assertEquals(s.getId(), matches.candidates(id(r)).getFirst().storeId());
    assertThrows(PublicDataException.class, () -> matches.confirm(id(r), s.getId(), false));
    matches.confirm(id(r), s.getId(), true);
    importer.savePage(List.of(r), seen.plusDays(1));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM public_store_records WHERE source_record_key=?",
            Integer.class,
            r.key()));
    assertEquals(s.getId(), records.findById(id(r)).orElseThrow().getMatchedStoreId());
    assertEquals("010", stores.findById(s.getId()).orElseThrow().getPhone());
    matches.unmatch(id(r), true);
    assertNull(records.findById(id(r)).orElseThrow().getMatchedStoreId());
  }

  @Test
  void pageFailureRollsBackAndLegacyDuplicatesAreNotGuessed() {
    var r = row();
    var bad = row();
    jdbc.execute(
        "CREATE TRIGGER test_reject_public_insert BEFORE INSERT ON public_store_records FOR EACH ROW BEGIN IF NEW.name='"
            + bad.name()
            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'; END IF; END");
    try {
      assertThrows(RuntimeException.class, () -> importer.savePage(List.of(r, bad), seen));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM public_store_records WHERE source_record_key=?",
              Integer.class,
              r.key()));
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_public_insert");
    }
    for (int i = 0; i < 2; i++)
      records.saveAndFlush(
          new PublicStoreRecord(
              "YPAY", null, "1", r.name(), null, null, r.address(), null, null, seen, null));
    assertThrows(PublicDataException.class, () -> importer.savePage(List.of(r), seen));
  }

  @Test
  void concurrentUpsertsHaveOneRecord() throws Exception {
    var r = row();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return importer.savePage(List.of(r), seen);
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return importer.savePage(List.of(r), seen);
              });
      start.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM public_store_records WHERE source_record_key=?",
              Integer.class,
              r.key()));
    }
  }

  @Test
  void differentAddressDoesNotMatch() {
    var r = row();
    importer.savePage(List.of(r), seen);
    store(r, "다른 주소");
    assertTrue(matches.candidates(id(r)).isEmpty());
  }
}
