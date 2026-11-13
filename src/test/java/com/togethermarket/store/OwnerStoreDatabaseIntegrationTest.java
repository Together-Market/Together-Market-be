package com.togethermarket.store;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.store.dto.request.StoreWriteRequest;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.store.service.OwnerStoreService;
import com.togethermarket.user.entity.User;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@Transactional
class OwnerStoreDatabaseIntegrationTest {
  @Autowired OwnerStoreService service;
  @Autowired UserRepository users;
  @Autowired StoreRepository stores;
  @Autowired EntityManager em;

  private Long owner() {
    return users
        .saveAndFlush(new User(UUID.randomUUID() + "@test.invalid", "hash", "사장", UserRole.OWNER))
        .getId();
  }

  private StoreWriteRequest createRequest(String name) {
    var body = new java.util.HashMap<String, Object>();
    body.put("name", name);
    body.put("business_type", "꽃집");
    body.put("address", "용인시");
    body.put("latitude", null);
    body.put("longitude", null);
    body.put("phone", "031-000-0000");
    return StoreWriteRequest.parse(body, true);
  }

  @Test
  void ownerPaginationAndPatchArePersisted() {
    Long owner = owner(), other = owner();
    var first = service.create(owner, createRequest("첫번째"));
    var second = service.create(owner, createRequest("두번째"));
    service.create(other, createRequest("다른 점주"));
    var page = service.list(owner, 1, 1);
    assertEquals(2, page.totalCount());
    assertEquals(second.id(), page.items().getFirst().id());
    assertEquals(first.id(), service.list(owner, 2, 1).items().getFirst().id());
    service.update(
        owner,
        Long.valueOf(first.id()),
        StoreWriteRequest.parse(Map.of("phone", "031-111-1111"), false));
    em.flush();
    em.clear();
    Store reloaded = stores.findById(Long.valueOf(first.id())).orElseThrow();
    assertEquals("031-111-1111", reloaded.getPhone());
    assertEquals("첫번째", reloaded.getName());
  }

  @Test
  void foreignOwnerAndMissingStoreHaveSame404() {
    Long owner = owner(), other = owner();
    var store = service.create(owner, createRequest("꽃집"));
    var patch = StoreWriteRequest.parse(Map.of("phone", "changed"), false);
    var foreign =
        assertThrows(
            OwnerStoreException.class,
            () -> service.update(other, Long.valueOf(store.id()), patch));
    var missing =
        assertThrows(OwnerStoreException.class, () -> service.update(other, Long.MAX_VALUE, patch));
    assertEquals(HttpStatus.NOT_FOUND, foreign.status());
    assertEquals(foreign.code(), missing.code());
  }
}
