package com.togethermarket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.store.repository.*;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @MockitoBean com.togethermarket.store.service.geocoding.AddressGeocoder geocoder;

  @MockitoBean
  com.togethermarket.recommendation.repository.RecommendationCandidateRepository candidates;

  @MockitoBean
  com.togethermarket.recommendation.repository.PurchaseRequestRepository purchaseRequests;

  @MockitoBean
  com.togethermarket.recommendation.repository.RecommendationItemRepository recommendationItems;

  @MockitoBean com.togethermarket.reservation.repository.ReservationRepository reservations;
  @MockitoBean com.togethermarket.reservation.repository.StoreOrderRepository orders;
  @MockitoBean org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean com.togethermarket.store.repository.PublicStoreRecordRepository publicRecords;
  @MockitoBean jakarta.persistence.EntityManager entityManager;
  @MockitoBean ProductRepository products;
  @MockitoBean StoreRepository stores;
  @MockitoBean UserRepository users;
  @MockitoBean StoreHourRepository hours;
  @MockitoBean ProductPurposeTagRepository tags;
  @MockitoBean com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;
  @Autowired MockMvc mvc;
  @Autowired PasswordEncoder passwords;
  @Autowired OwnerAccess ownerAccess;
  User user;

  @BeforeEach
  void setup() {
    user = new User("customer@example.com", passwords.encode("password"), "고객", UserRole.CUSTOMER);
    ReflectionTestUtils.setField(user, "id", 1L);
    when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    when(users.findById(1L)).thenReturn(Optional.of(user));
  }

  record TokenSession(MockHttpSession session, String token) {}

  TokenSession token(MockHttpSession session) throws Exception {
    var req = get("/auth/csrf");
    if (session != null) req.session(session);
    var result =
        mvc.perform(req)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.csrf_token").isString())
            .andReturn();
    var csrf = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
    return new TokenSession(
        (MockHttpSession) result.getRequest().getSession(false), csrf.getToken());
  }

  @Test
  void sessionLifecycleAndCsrfRotation() throws Exception {
    mvc.perform(get("/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(
            post("/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var before = token(null);
    String oldId = before.session().getId();
    mvc.perform(
            post("/auth/login")
                .session(before.session())
                .with(
                    request -> {
                      request.setRequestedSessionId(before.session().getId());
                      request.setRequestedSessionIdValid(true);
                      return request;
                    })
                .header("X-CSRF-TOKEN", before.token())
                .contentType("application/json")
                .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("1"))
        .andExpect(jsonPath("$.data.password_hash").doesNotExist());
    assertNotEquals(oldId, before.session().getId());
    mvc.perform(get("/auth/me").session(before.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
    mvc.perform(get("/owner/stores").session(before.session())).andExpect(status().isForbidden());
    mvc.perform(get("/owner/stores/geocode").param("address", "주소").session(before.session()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(geocoder);
    mvc.perform(
            post("/auth/logout").session(before.session()).header("X-CSRF-TOKEN", before.token()))
        .andExpect(status().isForbidden());
    var after = token(before.session());
    assertNotEquals(before.token(), after.token());
    mvc.perform(post("/auth/logout").session(after.session()).header("X-CSRF-TOKEN", after.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.logged_out").value(true));
    assertTrue(after.session().isInvalid());
    mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void wrongCredentialsAndInvalidBody() throws Exception {
    var t = token(null);
    mvc.perform(
            post("/auth/login")
                .session(t.session())
                .header("X-CSRF-TOKEN", t.token())
                .contentType("application/json")
                .content("{\"email\":\"customer@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mvc.perform(
            post("/auth/login")
                .session(t.session())
                .header("X-CSRF-TOKEN", t.token())
                .contentType("application/json")
                .content("{\"email\":\"invalid\",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unknownUserHasSameErrorAndCannotCreateAuthenticatedSession() throws Exception {
    var t = token(null);
    mvc.perform(
            post("/auth/login")
                .session(t.session())
                .header("X-CSRF-TOKEN", t.token())
                .contentType("application/json")
                .content("{\"email\":\"unknown@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mvc.perform(get("/auth/me").session(t.session())).andExpect(status().isUnauthorized());
  }

  @Test
  void ownerPrincipalWorksWithExistingPort() throws Exception {
    var owner = new User("owner@example.com", passwords.encode("password"), "점주", UserRole.OWNER);
    ReflectionTestUtils.setField(owner, "id", 2L);
    when(users.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
    when(users.findById(2L)).thenReturn(Optional.of(owner));
    when(stores.findByOwnerId(
            org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    var t = token(null);
    mvc.perform(
            post("/auth/login")
                .session(t.session())
                .header("X-CSRF-TOKEN", t.token())
                .contentType("application/json")
                .content("{\"email\":\"owner@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/owner/stores").session(t.session())).andExpect(status().isOk());
    when(geocoder.lookup("도로 주소 1"))
        .thenReturn(
            new com.togethermarket.store.service.geocoding.Coordinates(
                new java.math.BigDecimal("37.2758538"), new java.math.BigDecimal("127.1512813")));
    mvc.perform(get("/owner/stores/geocode").session(t.session()).param("address", "도로 주소 1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.storage_allowed").value(false));
    mvc.perform(get("/owner/stores/geocode").param("address", "도로 주소 1"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            patch("/owner/stores/1")
                .session(t.session())
                .contentType("application/json")
                .content("{\"phone\":\"new\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    verify(geocoder, times(1)).lookup("도로 주소 1");
  }

  @Test
  void allowedPreflightDoesNotRequireLoginOrCsrf() throws Exception {
    mvc.perform(
            options("/reservations")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers", "content-type,x-csrf-token,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    org.hamcrest.Matchers.containsString("idempotency-key")));
    verifyNoInteractions(geocoder);
  }

  @Test
  void otherOriginsAndUnsupportedPreflightHeadersAreRejected() throws Exception {
    for (String origin :
        new String[] {
          "http://localhost:5174", "http://127.0.0.1:5173", "https://untrusted.invalid", "null"
        }) {
      mvc.perform(
              options("/auth/login")
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", "POST"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("CORS_REJECTED"))
          .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    mvc.perform(
            options("/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-unapproved-header"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CORS_REJECTED"));
  }

  @Test
  void corsDoesNotBypassSessionAuthenticationOrCsrf() throws Exception {
    mvc.perform(get("/auth/me").header("Origin", "http://localhost:5173"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    mvc.perform(
            post("/auth/login")
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  @Test
  void allowedOriginCanLoginAndReadItsSession() throws Exception {
    var before = token(null);
    mvc.perform(
            post("/auth/login")
                .session(before.session())
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-TOKEN", before.token())
                .contentType("application/json")
                .content("{\"email\":\"customer@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    mvc.perform(get("/auth/me").session(before.session()).header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
    mvc.perform(
            post("/auth/logout")
                .session(before.session())
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-TOKEN", before.token()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var after = token(before.session());
    mvc.perform(
            post("/auth/logout")
                .session(after.session())
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-TOKEN", after.token()))
        .andExpect(status().isOk());
  }
}
