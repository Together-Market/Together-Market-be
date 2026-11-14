package com.togethermarket.store;

import static org.assertj.core.api.Assertions.*;

import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.CoordinateBackfillRepository;
import com.togethermarket.store.service.geocoding.Coordinates;
import com.togethermarket.user.entity.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in dedicated test DB only. Exercises MySQL null-safe comparison and atomic update. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=", "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
          "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate", "demo.catalog.enabled=false"
    })
@Transactional
class CoordinateBackfillDatabaseIntegrationTest {
  @Autowired EntityManager em;
  @Autowired CoordinateBackfillRepository repository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void conditionalFillReplacesHalfPairAndPreservesOwnerEditsOnRerun() {
    var owner = new User(UUID.randomUUID() + "@test.invalid", "test", "점주", UserRole.OWNER);
    em.persist(owner);
    var store =
        new Store(
            owner.getId(), "점포", "카페", "경기도 용인시 도로 1", new BigDecimal("38"), null, "031-000-0000");
    em.persist(store);
    var pair = new Coordinates(new BigDecimal("37.2758538"), new BigDecimal("127.1512813"));
    var record =
        new PublicStoreRecord(
            "SBIZ",
            "test-" + UUID.randomUUID(),
            null,
            "점포",
            null,
            "카페",
            store.getAddress(),
            pair.latitude(),
            pair.longitude(),
            LocalDateTime.now(),
            store.getId());
    em.persist(record);
    em.flush();
    var snapshot = repository.missing(store.getId() - 1, store.getId(), 10).getFirst();
    var source = repository.linked(store.getId()).getFirst();
    assertThat(repository.fill(snapshot, source, pair)).isTrue();
    assertThat(repository.fill(snapshot, source, pair)).isFalse();
    em.clear();
    assertThat(em.find(Store.class, store.getId()).getLatitude())
        .isEqualByComparingTo(pair.latitude());
    assertThat(repository.missing(store.getId() - 1, store.getId(), 10)).isEmpty();
    jdbc.update("UPDATE stores SET longitude=NULL, address=? WHERE id=?", "다른 주소", store.getId());
    assertThat(repository.fill(snapshot, source, pair)).isFalse();
    var changed = repository.missing(store.getId() - 1, store.getId(), 10).getFirst();
    jdbc.update("UPDATE public_store_records SET matched_store_id=NULL WHERE id=?", record.getId());
    assertThat(repository.fill(changed, source, pair)).isFalse();
  }
}
