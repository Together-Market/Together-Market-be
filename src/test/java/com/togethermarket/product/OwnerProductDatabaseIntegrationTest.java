package com.togethermarket.product;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.product.dto.request.ProductWriteRequest;
import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.OwnerProductService;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
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
class OwnerProductDatabaseIntegrationTest {
  @Autowired OwnerProductService service;
  @Autowired ProductRepository products;
  @Autowired ProductPurposeTagRepository tags;
  @Autowired StoreRepository stores;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;

  private Store setup() {
    var user =
        users.saveAndFlush(
            new User(UUID.randomUUID() + "@test.invalid", "hash", "사장", UserRole.OWNER));
    return stores.saveAndFlush(new Store(user.getId(), "꽃집", "꽃집", "용인", null, null, "010"));
  }

  private ProductWriteRequest create() {
    Map<String, Object> body =
        new HashMap<>(
            Map.of(
                "name",
                "꽃다발",
                "description",
                "생일",
                "price_amount",
                30000,
                "preparation_minutes",
                60,
                "sale_status",
                "ON_SALE",
                "purpose_category_ids",
                List.of("11")));
    body.put("image_url", null);
    return ProductWriteRequest.parse(body, true);
  }

  private ProductWriteRequest patch(Map<String, Object> body) {
    return ProductWriteRequest.parse(body, false);
  }

  @Test
  void createPatchSameTagFilterAndStatusRoundTrip() {
    Store s = setup();
    var p = service.create(s.getOwnerId(), s.getId(), create());
    Long id = Long.valueOf(p.id());
    service.update(
        s.getOwnerId(),
        s.getId(),
        id,
        patch(Map.of("name", "새 꽃다발", "purpose_category_ids", List.of("11", "12"))));
    // Replacing the same composite key again must not lose tags or hit duplicate-key errors.
    service.update(
        s.getOwnerId(), s.getId(), id, patch(Map.of("purpose_category_ids", List.of("11", "12"))));
    assertEquals(2, tags.findByIdProductId(id).size());
    assertEquals("새 꽃다발", products.findById(id).orElseThrow().getName());
    service.status(s.getOwnerId(), s.getId(), id, SaleStatus.SOLD_OUT);
    assertEquals(
        0, service.list(s.getOwnerId(), s.getId(), 1, 20, SaleStatus.ON_SALE).totalCount());
    assertEquals(1, service.list(s.getOwnerId(), s.getId(), 1, 20, null).totalCount());
    assertEquals(
        List.of("11", "12"),
        service
            .list(s.getOwnerId(), s.getId(), 1, 20, SaleStatus.SOLD_OUT)
            .items()
            .getFirst()
            .purposeCategoryIds());
  }

  @Test
  void tagInsertFailureRollsBackProductCreateAndUpdate() {
    Store s = setup();
    var p = service.create(s.getOwnerId(), s.getId(), create());
    Long id = Long.valueOf(p.id());
    jdbc.execute(
        "CREATE TRIGGER test_reject_product_tag BEFORE INSERT ON product_purpose_tags FOR EACH ROW BEGIN IF NEW.purpose_category_id = 12 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected'; END IF; END");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              service.update(
                  s.getOwnerId(),
                  s.getId(),
                  id,
                  patch(Map.of("name", "rollback", "purpose_category_ids", List.of("12")))));
      assertEquals("꽃다발", products.findById(id).orElseThrow().getName());
      assertEquals(11L, tags.findByIdProductId(id).getFirst().getId().getPurposeCategoryId());
      var input = create();
      var failed =
          new ProductWriteRequest(
              input.name(),
              input.description(),
              input.imageUrl(),
              input.priceAmount(),
              input.preparationMinutes(),
              input.saleStatus(),
              List.of(12L),
              input.fields());
      assertThrows(RuntimeException.class, () -> service.create(s.getOwnerId(), s.getId(), failed));
      assertEquals(1, service.list(s.getOwnerId(), s.getId(), 1, 20, null).totalCount());
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_product_tag");
    }
  }

  @Test
  void concurrentDetailsAndStatusDoNotLoseUpdates() throws Exception {
    Store s = setup();
    var p = service.create(s.getOwnerId(), s.getId(), create());
    Long id = Long.valueOf(p.id());
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var details =
          executor.submit(
              () -> {
                start.await();
                return service.update(s.getOwnerId(), s.getId(), id, patch(Map.of("name", "변경")));
              });
      var status =
          executor.submit(
              () -> {
                start.await();
                return service.status(s.getOwnerId(), s.getId(), id, SaleStatus.PAUSED);
              });
      start.countDown();
      details.get(10, TimeUnit.SECONDS);
      status.get(10, TimeUnit.SECONDS);
      var saved = products.findById(id).orElseThrow();
      assertEquals("변경", saved.getName());
      assertEquals(SaleStatus.PAUSED, saved.getSaleStatus());
    }
  }
}
