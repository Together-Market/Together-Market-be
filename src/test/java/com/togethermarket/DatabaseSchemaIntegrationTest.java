package com.togethermarket;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.recommendation.entity.PurchaseRequest;
import com.togethermarket.recommendation.entity.RecommendationStatus;
import com.togethermarket.recommendation.repository.PurchaseRequestRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Run only against a dedicated MySQL test database; Flyway applies the schema before tests. */
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
class DatabaseSchemaIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PurchaseRequestRepository requests;
  @Autowired private EntityManager entityManager;

  private void assertCheckViolation(Runnable operation) {
    DataAccessException failure = assertThrows(DataAccessException.class, operation::run);
    Throwable cause = failure.getMostSpecificCause();
    assertInstanceOf(SQLException.class, cause);
    assertEquals(3819, ((SQLException) cause).getErrorCode());
  }

  @Test
  void jsonAndEnumRoundTripThroughJpa() {
    long customer = user();
    var request =
        requests.saveAndFlush(
            new PurchaseRequest(
                customer,
                11L,
                BigDecimal.valueOf(1000),
                LocalDateTime.of(2026, 11, 16, 12, 0),
                RecommendationStatus.RECOMMENDING,
                null,
                LocalDateTime.of(2026, 11, 13, 12, 0),
                "[{\"code\":\"style\",\"value\":\"따뜻한\"}]"));
    Long id = request.getId();
    entityManager.clear();
    var loaded = requests.findById(id).orElseThrow();
    assertEquals(RecommendationStatus.RECOMMENDING, loaded.getStatus());
    assertTrue(loaded.getAnswers().contains("따뜻한"));
    assertEquals(
        "ARRAY",
        jdbc.queryForObject(
            "SELECT JSON_TYPE(answers) FROM purchase_requests WHERE id = ?", String.class, id));
  }

  private long user() {
    String email = UUID.randomUUID() + "@test.invalid";
    jdbc.update(
        "INSERT INTO users(email,password_hash,name,role) VALUES (?, 'test-hash', 'owner', 'OWNER')",
        email);
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
  }

  private long store(long owner) {
    jdbc.update(
        "INSERT INTO stores(owner_id,name,business_type,address,phone) VALUES (?, 'store', 'flower', 'address', '010')",
        owner);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  @Test
  void migrationsAndPurposeSeedsArePresent() {
    assertEquals(25, jdbc.queryForObject("SELECT COUNT(*) FROM purpose_categories", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM purpose_categories WHERE parent_id IS NULL", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND type = 'SQL'",
            Integer.class));
  }

  @Test
  void duplicateEmailIsRejected() {
    long id = user();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO users(email,password_hash,name,role) SELECT email,password_hash,name,role FROM users WHERE id = ?",
                id));
  }

  @Test
  void invalidScheduleAndNegativePriceAreRejected() {
    long storeId = store(user());
    assertCheckViolation(
        () ->
            jdbc.update(
                "INSERT INTO store_hours(store_id,weekday,target_date,hour_type,is_closed,end_day_offset) VALUES (?,1,'2026-11-16','BUSINESS',1,0)",
                storeId));
    assertCheckViolation(
        () ->
            jdbc.update(
                "INSERT INTO store_hours(store_id,weekday,hour_type,is_closed,end_day_offset) VALUES (?,1,'BUSINESS',0,0)",
                storeId));
    assertCheckViolation(
        () ->
            jdbc.update(
                "INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES (?,'x','x',-1,0,'ON_SALE')",
                storeId));
    jdbc.update(
        "INSERT INTO store_hours(store_id,weekday,hour_type,is_closed,opens_at,closes_at,end_day_offset) VALUES (?,1,'BUSINESS',0,'22:00','02:00',1)",
        storeId);
  }

  @Test
  void orderMustUseProductFromItsOwnStoreAndRequestKeysAreUnique() {
    long owner = user();
    long first = store(owner);
    long second = store(owner);
    jdbc.update(
        "INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES (?,'x','x',100,0,'ON_SALE')",
        first);
    long product = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO purchase_requests(customer_id,purpose_category_id,budget_amount,preferred_pickup_at,status,created_at,answers) VALUES (?,11,1000,'2026-11-16 12:00:00','READY',NOW(),'[]')",
        owner);
    long request = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO recommendation_candidates(purchase_request_id,`rank`,recommendation_reason) VALUES (?,1,'test')",
        request);
    long candidate = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO reservations(customer_id,recommendation_candidate_id,status,customer_name,customer_phone,approval_deadline_at,request_key,request_payload_hash,created_at) VALUES (?,?,'PENDING','test','010',NOW(),'key',REPEAT('a',64),NOW())",
        owner,
        candidate);
    long reservation = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO reservations(customer_id,recommendation_candidate_id,status,customer_name,customer_phone,approval_deadline_at,request_key,request_payload_hash,created_at) VALUES (?,?,'PENDING','test','010',NOW(),'key',REPEAT('b',64),NOW())",
                owner,
                candidate));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO store_orders(reservation_id,store_id,product_id,quantity,unit_price_snapshot,product_name_snapshot,pickup_address_snapshot,requested_pickup_at,approval_status) VALUES (?,?,?,1,100,'x','address',NOW(),'PENDING')",
                reservation,
                second,
                product));
  }
}
