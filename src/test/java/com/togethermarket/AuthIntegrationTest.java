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

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {
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
  }
}
