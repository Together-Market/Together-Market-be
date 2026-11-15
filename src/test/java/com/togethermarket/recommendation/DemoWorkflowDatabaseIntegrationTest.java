package com.togethermarket.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.recommendation.service.*;
import com.togethermarket.recommendation.service.RecommendationInput.Evaluation;
import com.togethermarket.store.entity.*;
import com.togethermarket.store.repository.*;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

/** Disposable MySQL only. LIVE_GEMINI_TEST=true runs the selected happy-path against Google. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=",
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=${MYSQL_TEST_USERNAME:root}",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
      "GEMINI_API_KEY=${LIVE_GEMINI_KEY:test-key}",
      // This scenario owns its catalog; CSV startup import is tested in a separate database.
      "demo.catalog.enabled=false",
      "reservation.expiry-scheduler.enabled=false"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DemoWorkflowDatabaseIntegrationTest {
  static final AtomicReference<String> BEHAVIOR = new AtomicReference<>("OK");

  @TestConfiguration(proxyBeanMethods = false)
  static class EvaluationConfig {
    @Bean
    @Primary
    CandidateEvaluator testEvaluator(Environment env) {
      if (env.getProperty("LIVE_GEMINI_TEST", Boolean.class, false))
        return new GeminiCandidateEvaluator(
            env.getRequiredProperty("LIVE_GEMINI_KEY"), GeminiCandidateEvaluator.DEFAULT_MODEL);
      return (context, candidates) -> {
        if (BEHAVIOR.get().equals("ERROR"))
          throw new IllegalArgumentException("invalid test response");
        return candidates.stream()
            .map(
                c ->
                    new Evaluation(
                        c.candidate_id(),
                        5,
                        4,
                        4,
                        5,
                        BEHAVIOR.get().equals("EMPTY") ? "EXCLUDED" : "ALLOWED",
                        c.products().stream().map(p -> p.id()).toList(),
                        "시연용 검증 조합"))
            .toList();
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired UserRepository users;
  @Autowired StoreRepository stores;
  @Autowired StoreHourRepository hours;
  @Autowired ProductRepository products;
  @Autowired ProductPurposeTagRepository tags;
  @Autowired PasswordEncoder passwords;
  @Autowired JdbcTemplate jdbc;
  @Autowired RankingModel model;
  long customer, other, ownerA, ownerB, storeA, storeB, productA;
  String email, ownerEmailA, ownerEmailB, otherEmail;
  OffsetDateTime pickup;

  record Login(MockHttpSession session, String csrf) {}

  @BeforeEach
  void seed() {
    BEHAVIOR.set("OK");
    String suffix = UUID.randomUUID() + "@example.invalid";
    email = "c" + suffix;
    otherEmail = "d" + suffix;
    ownerEmailA = "a" + suffix;
    ownerEmailB = "b" + suffix;
    String hash = passwords.encode("demo-test-password");
    customer = users.saveAndFlush(new User(email, hash, "고객", UserRole.CUSTOMER)).getId();
    other = users.saveAndFlush(new User(otherEmail, hash, "다른 고객", UserRole.CUSTOMER)).getId();
    ownerA = users.saveAndFlush(new User(ownerEmailA, hash, "점주A", UserRole.OWNER)).getId();
    ownerB = users.saveAndFlush(new User(ownerEmailB, hash, "점주B", UserRole.OWNER)).getId();
    storeA =
        stores
            .saveAndFlush(
                new Store(
                    ownerA,
                    "시연 꽃집",
                    "꽃집",
                    "용인시 시연 주소 A",
                    new BigDecimal("37.32"),
                    new BigDecimal("127.110"),
                    "031-000-0000"))
            .getId();
    storeB =
        stores
            .saveAndFlush(
                new Store(
                    ownerB,
                    "시연 제과점",
                    "제과점",
                    "용인시 시연 주소 B",
                    new BigDecimal("37.321"),
                    new BigDecimal("127.111"),
                    "031-000-0001"))
            .getId();
    pickup =
        LocalDate.now(ZoneId.of("Asia/Seoul"))
            .plusDays(2)
            .atTime(15, 0)
            .atOffset(ZoneOffset.ofHours(9));
    for (long store : List.of(storeA, storeB))
      for (HourType type : HourType.values())
        hours.saveAndFlush(
            new StoreHour(
                store,
                null,
                pickup.toLocalDate(),
                type,
                false,
                LocalTime.of(9, 0),
                LocalTime.of(22, 0),
                (short) 0));
    var a =
        products.saveAndFlush(
            new Product(
                storeA, "생일 꽃다발", "따뜻한 색감", null, new BigDecimal("30000"), 30, SaleStatus.ON_SALE));
    var b =
        products.saveAndFlush(
            new Product(
                storeB,
                "생일 홀케이크 1호",
                "친구와 나눌 생크림 홀케이크 1개",
                null,
                new BigDecimal("25000"),
                30,
                SaleStatus.ON_SALE));
    var c =
        products.saveAndFlush(
            new Product(
                storeB,
                "생일 홀케이크 2호",
                "친구들과 나눌 홀케이크 한 개",
                null,
                new BigDecimal("15000"),
                15,
                SaleStatus.ON_SALE));
    // A rejected birthday reservation needs another pair that still includes a whole cake.
    var d =
        products.saveAndFlush(
            new Product(
                storeB,
                "생일 초코 홀케이크 1호",
                "대체 예약용 초코 홀케이크 1개",
                null,
                new BigDecimal("20000"),
                30,
                SaleStatus.ON_SALE));
    productA = a.getId();
    for (var p : List.of(a, b, c, d)) tags.saveAndFlush(new ProductPurposeTag(p.getId(), 11L));
  }

  @AfterEach
  void retainNoSellableTestCatalog() {
    jdbc.update("update products set sale_status='PAUSED' where store_id in (?,?)", storeA, storeB);
    BEHAVIOR.set("OK");
  }

  Login login(String address) throws Exception {
    var token = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
    var session = (MockHttpSession) token.getRequest().getSession(false);
    var csrf = (CsrfToken) token.getRequest().getAttribute(CsrfToken.class.getName());
    mvc.perform(
            post("/auth/login")
                .session(session)
                .header("X-CSRF-Token", csrf.getToken())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of("email", address, "password", "demo-test-password"))))
        .andExpect(status().isOk());
    var fresh = mvc.perform(get("/auth/csrf").session(session)).andReturn();
    return new Login(
        session,
        ((CsrfToken) fresh.getRequest().getAttribute(CsrfToken.class.getName())).getToken());
  }

  String body() throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "purpose_category_id",
            "11",
            "budget_amount",
            60000,
            "preferred_pickup_at",
            pickup.toString(),
            "answers",
            List.of(
                Map.of("question_code", "style", "value", "따뜻한 스타일"),
                Map.of("question_code", "recipient", "value", "생일인 친구"),
                Map.of("question_code", "category_preference", "value", "꽃과 디저트"))));
  }

  JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  String create(Login login) throws Exception {
    var result =
        mvc.perform(
                post("/purchase-requests")
                    .session(login.session())
                    .header("X-CSRF-Token", login.csrf())
                    .contentType("application/json")
                    .content(body()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("RECOMMENDING"))
            .andExpect(jsonPath("$.data.id").isString())
            .andReturn();
    return json(result).path("data").path("id").asString();
  }

  JsonNode awaitTerminal(String request, Login login) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < deadline) {
      var data =
          json(mvc.perform(get("/purchase-requests/" + request).session(login.session()))
                  .andExpect(status().isOk())
                  .andReturn())
              .path("data");
      if (!data.path("status").asString().equals("RECOMMENDING")) return data;
      Thread.sleep(100);
    }
    throw new AssertionError("Recommendation did not finish");
  }

  @Test
  void happyPathThroughActualRankingReservationAndBothOwners() throws Exception {
    assertThat(model.mode()).isEqualTo("demo");
    var customerLogin = login(email);
    mvc.perform(get("/purpose-categories").session(customerLogin.session()))
        .andExpect(status().isOk());
    mvc.perform(get("/purpose-categories/11/questions").session(customerLogin.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.questions.length()").value(5));
    String request = create(customerLogin);
    assertThat(awaitTerminal(request, customerLogin).path("status").asString()).isEqualTo("READY");
    var list =
        json(mvc.perform(
                    get("/purchase-requests/" + request + "/recommendations")
                        .session(customerLogin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total_count").isNumber())
                .andReturn())
            .path("data");
    assertThat(list.path("items").size()).isGreaterThan(0);
    for (var recommendation : list.path("items")) {
      int wholeCakes = 0;
      for (var item : recommendation.path("items")) {
        var product = item.path("product");
        if (com.togethermarket.recommendation.service.candidate.GiftComposition.wholeCake(
            product.path("name").asString(), product.path("description").asString())) wholeCakes++;
      }
      assertThat(wholeCakes).isEqualTo(1);
    }
    var candidate = list.path("items").get(0);
    String candidateId = candidate.path("id").asString();
    mvc.perform(get("/recommendations/" + candidateId).session(customerLogin.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.store_hours.length()").value(2));
    mvc.perform(
            get("/recommendations/" + candidateId + "/locations").session(customerLogin.session()))
        .andExpect(status().isOk());
    mvc.perform(get("/products/" + productA).session(customerLogin.session()))
        .andExpect(status().isOk());
    mvc.perform(get("/stores/" + storeA).session(customerLogin.session()))
        .andExpect(status().isOk());
    List<Map<String, String>> pickups = new ArrayList<>();
    for (var item : candidate.path("items"))
      pickups.add(
          Map.of(
              "store_id",
              item.path("store").path("id").asString(),
              "requested_pickup_at",
              pickup.toString()));
    assertThat(pickups)
        .extracting(p -> p.get("store_id"))
        .containsExactlyInAnyOrder(Long.toString(storeA), Long.toString(storeB));
    String reservationBody =
        mapper.writeValueAsString(
            Map.of(
                "recommendation_candidate_id",
                candidateId,
                "customer_name",
                "시연 고객",
                "customer_phone",
                "010-0000-0000",
                "pickups",
                pickups,
                "expected_total_amount",
                candidate.path("total_amount").asLong()));
    String key = UUID.randomUUID().toString();
    var result =
        json(mvc.perform(
                    post("/reservations")
                        .session(customerLogin.session())
                        .header("X-CSRF-Token", customerLogin.csrf())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(reservationBody))
                .andExpect(status().isCreated())
                .andReturn())
            .path("data");
    mvc.perform(
            post("/reservations")
                .session(customerLogin.session())
                .header("X-CSRF-Token", customerLogin.csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(reservationBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(result.path("id").asString()));
    int index = 0;
    for (var order : result.path("orders")) {
      var ownerLogin =
          login(
              order.path("store_id").asString().equals(Long.toString(storeA))
                  ? ownerEmailA
                  : ownerEmailB);
      mvc.perform(
              post("/owner/stores/"
                      + order.path("store_id").asString()
                      + "/orders/"
                      + order.path("id").asString()
                      + "/approve")
                  .session(ownerLogin.session())
                  .header("X-CSRF-Token", ownerLogin.csrf()))
          .andExpect(status().isOk());
      mvc.perform(
              get("/reservations/" + result.path("id").asString()).session(customerLogin.session()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value(index++ == 0 ? "PENDING" : "CONFIRMED"));
    }
    // A new reservation is actually rejected by its owner; no fabricated state transition.
    var failedReservation =
        json(mvc.perform(
                    post("/reservations")
                        .session(customerLogin.session())
                        .header("X-CSRF-Token", customerLogin.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(reservationBody))
                .andExpect(status().isCreated())
                .andReturn())
            .path("data");
    var rejectedOrder = failedReservation.path("orders").get(0);
    var rejectingOwner =
        login(
            rejectedOrder.path("store_id").asString().equals(Long.toString(storeA))
                ? ownerEmailA
                : ownerEmailB);
    mvc.perform(
            post("/owner/stores/"
                    + rejectedOrder.path("store_id").asString()
                    + "/orders/"
                    + rejectedOrder.path("id").asString()
                    + "/reject")
                .session(rejectingOwner.session())
                .header("X-CSRF-Token", rejectingOwner.csrf())
                .contentType("application/json")
                .content("{\"rejection_reason\":\"시연 재고 확인 거절\"}"))
        .andExpect(status().isOk());
    var alternatives =
        json(mvc.perform(
                    get("/reservations/"
                            + failedReservation.path("id").asString()
                            + "/alternatives")
                        .session(customerLogin.session()))
                .andExpect(status().isOk())
                .andReturn())
            .path("data");
    if (!Boolean.parseBoolean(System.getenv("LIVE_GEMINI_TEST")))
      assertThat(alternatives.path("items").size()).isGreaterThan(0);
    for (var alternative : alternatives.path("items"))
      assertThat(alternative.path("id").asString()).isNotEqualTo(candidateId);
    var outsider = login(otherEmail);
    mvc.perform(get("/purchase-requests/" + request).session(outsider.session()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/recommendations/" + candidateId).session(outsider.session()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/recommendations/" + candidateId + "/locations").session(outsider.session()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/reservations/" + failedReservation.path("id").asString() + "/alternatives")
                .session(outsider.session()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/purchase-requests/" + request + "/recommendations?page=0")
                .session(customerLogin.session()))
        .andExpect(status().isBadRequest());
    // All candidates use the first store's only product.
    jdbc.update("update products set price_amount=20000 where id=?", productA);
    mvc.perform(get("/recommendations/" + candidateId).session(customerLogin.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availability_status").value("AVAILABLE"));
    jdbc.update("update products set sale_status='PAUSED' where id=?", productA);
    mvc.perform(get("/recommendations/" + candidateId).session(customerLogin.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availability_status").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.unavailable_reasons[0].code").isString());
  }

  @Test
  void authenticationValidationEmptyAndFailureContracts() throws Exception {
    mvc.perform(get("/purpose-categories")).andExpect(status().isUnauthorized());
    var owner = login(ownerEmailA);
    mvc.perform(get("/purpose-categories").session(owner.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
    mvc.perform(get("/purpose-categories/11/questions").session(owner.session()))
        .andExpect(status().isForbidden());
    var customerLogin = login(email);
    mvc.perform(
            post("/purchase-requests")
                .session(customerLogin.session())
                .contentType("application/json")
                .content(body()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/purchase-requests")
                .session(customerLogin.session())
                .header("X-CSRF-Token", customerLogin.csrf())
                .contentType("application/json")
                .content(body().replace("\"budget_amount\":60000", "\"budget_amount\":\"60000\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("budget_amount"));
    BEHAVIOR.set("EMPTY");
    var empty = create(customerLogin);
    assertThat(awaitTerminal(empty, customerLogin).path("status").asString()).isEqualTo("READY");
    mvc.perform(
            get("/purchase-requests/" + empty + "/recommendations")
                .session(customerLogin.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_count").value(0))
        .andExpect(jsonPath("$.data.items.length()").value(0));
    BEHAVIOR.set("ERROR");
    var failed = create(customerLogin);
    assertThat(awaitTerminal(failed, customerLogin).path("status").asString()).isEqualTo("FAILED");
    mvc.perform(
            get("/purchase-requests/" + failed + "/recommendations")
                .session(customerLogin.session()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RECOMMENDATION_FAILED"));
  }
}
