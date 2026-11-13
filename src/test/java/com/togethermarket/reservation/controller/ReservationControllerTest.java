package com.togethermarket.reservation.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.*;
import com.togethermarket.reservation.dto.response.ReservationResponse;
import com.togethermarket.reservation.service.ReservationService;
import com.togethermarket.user.entity.UserRole;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfiguration.class)
class ReservationControllerTest {
  @MockitoBean com.togethermarket.user.repository.UserRepository users;
  @MockitoBean com.togethermarket.product.repository.ProductRepository products;
  @MockitoBean com.togethermarket.store.repository.StoreRepository stores;
  @MockitoBean com.togethermarket.store.repository.StoreHourRepository hours;
  @MockitoBean com.togethermarket.product.repository.ProductPurposeTagRepository tags;
  @MockitoBean com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;
  @Autowired MockMvc mvc;
  @MockitoBean ReservationService service;
  static final String BODY =
      """
 {"recommendation_candidate_id":"200","customer_name":"고객","customer_phone":"010","pickups":[{"store_id":"3","requested_pickup_at":"2026-11-20T15:00:00+09:00"},{"store_id":"4","requested_pickup_at":"2026-11-20T15:15:00+09:00"}],"expected_total_amount":55000}
 """;

  RequestPostProcessor as(UserRole role) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new LoginUser(1L, role),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }

  ReservationResponse response() {
    return new ReservationResponse(
        "500",
        "1",
        "200",
        "PENDING",
        null,
        "고객",
        "010",
        null,
        OffsetDateTime.parse("2026-11-20T10:30:00+09:00"),
        OffsetDateTime.parse("2026-11-20T10:00:00+09:00"),
        new BigDecimal("55000"),
        List.of());
  }

  @Test
  void creationAndReplayUseDifferentStatusWithSameResponseShape() throws Exception {
    when(service.create(eq(1L), anyString(), any()))
        .thenReturn(
            new ReservationService.Created(response(), false),
            new ReservationService.Created(response(), true));
    for (int status : List.of(201, 200))
      mvc.perform(
              post("/reservations")
                  .with(as(UserRole.CUSTOMER))
                  .with(csrf().asHeader())
                  .header("Idempotency-Key", "b492964a-c36b-4436-8a4e-d3e2e7cd138e")
                  .contentType("application/json")
                  .content(BODY))
          .andExpect(status().is(status))
          .andExpect(jsonPath("$.data.customer_id").value("1"))
          .andExpect(jsonPath("$.data.total_amount").value(55000))
          .andExpect(jsonPath("$.data.approval_deadline_at").value("2026-11-20T10:30:00+09:00"));
  }

  @Test
  void unauthenticatedAndOwnerAndMissingCsrfCannotCreate() throws Exception {
    mvc.perform(post("/reservations").with(csrf()).contentType("application/json").content(BODY))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/reservations")
                .with(as(UserRole.OWNER))
                .with(csrf())
                .contentType("application/json")
                .content(BODY))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/reservations")
                .with(as(UserRole.CUSTOMER))
                .contentType("application/json")
                .content(BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    verifyNoInteractions(service);
  }

  @Test
  void malformedInputAndStatusUseCommonErrors() throws Exception {
    mvc.perform(
            post("/reservations")
                .with(as(UserRole.CUSTOMER))
                .with(csrf())
                .contentType("application/json")
                .content(BODY.replace("55000", "-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/reservations").with(as(UserRole.CUSTOMER)).param("status", "UNKNOWN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verifyNoInteractions(service);
  }
}
