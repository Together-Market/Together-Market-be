package com.togethermarket.reservation.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.*;
import com.togethermarket.reservation.dto.response.OwnerOrderResponse;
import com.togethermarket.reservation.service.OwnerOrderService;
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
class OwnerOrderControllerTest {
  @MockitoBean com.togethermarket.user.repository.UserRepository users;
  @MockitoBean com.togethermarket.product.repository.ProductRepository products;
  @MockitoBean com.togethermarket.store.repository.StoreRepository stores;
  @MockitoBean com.togethermarket.store.repository.StoreHourRepository hours;
  @MockitoBean com.togethermarket.product.repository.ProductPurposeTagRepository tags;
  @MockitoBean com.togethermarket.recommendation.repository.PurposeCategoryRepository categories;
  @MockitoBean com.togethermarket.reservation.repository.ReservationRepository reservations;
  @MockitoBean com.togethermarket.reservation.repository.StoreOrderRepository orders;
  @Autowired MockMvc mvc;
  @MockitoBean ReservationService customerReservations;
  @MockitoBean OwnerOrderService service;

  RequestPostProcessor as(UserRole role) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new LoginUser(9L, role),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }

  OwnerOrderResponse response() {
    return new OwnerOrderResponse(
        "600",
        "500",
        "3",
        "꽃집",
        "10",
        1,
        new BigDecimal("30000"),
        "꽃",
        "주소",
        OffsetDateTime.parse("2026-11-20T15:00:00+09:00"),
        "APPROVED",
        OffsetDateTime.parse("2026-11-20T10:00:00+09:00"),
        null,
        "PENDING",
        null,
        OffsetDateTime.parse("2026-11-20T10:30:00+09:00"),
        "고객",
        "010",
        null,
        false);
  }

  @Test
  void approveResponseContainsOnlyOwnOrderAndWholeReservationStatus() throws Exception {
    when(service.approve(9L, 3L, 600L)).thenReturn(response());
    mvc.perform(
            post("/owner/stores/3/orders/600/approve")
                .with(as(UserRole.OWNER))
                .with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("600"))
        .andExpect(jsonPath("$.data.reservation_status").value("PENDING"))
        .andExpect(jsonPath("$.data.can_respond").value(false))
        .andExpect(jsonPath("$.data.orders").doesNotExist());
  }

  @Test
  void customerAnonymousAndMissingCsrfCannotDecide() throws Exception {
    mvc.perform(post("/owner/stores/3/orders/600/approve").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/owner/stores/3/orders/600/approve").with(as(UserRole.CUSTOMER)).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/owner/stores/3/orders/600/approve").with(as(UserRole.OWNER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    verifyNoInteractions(service);
  }

  @Test
  void rejectReasonIsValidatedAndPassedWithoutModification() throws Exception {
    mvc.perform(
            post("/owner/stores/3/orders/600/reject")
                .with(as(UserRole.OWNER))
                .with(csrf())
                .contentType("application/json")
                .content("{\"rejection_reason\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("rejection_reason"))
        .andExpect(jsonPath("$.errors[0].reason").isString());
    mvc.perform(
            post("/owner/stores/3/orders/600/reject")
                .with(as(UserRole.OWNER))
                .with(csrf())
                .contentType("application/json")
                .content("{\"rejection_reason\":\"재고 없음\"}"))
        .andExpect(status().isOk());
    verify(service).reject(9L, 3L, 600L, "재고 없음");
  }

  @Test
  void filtersAreCombinedAndInvalidEnumsUseCommonErrors() throws Exception {
    when(service.list(eq(9L), eq(3L), eq(1), eq(20), any(), any()))
        .thenReturn(new OwnerOrderResponse.Page(List.of(), 1, 20, 0));
    mvc.perform(
            get("/owner/stores/3/orders")
                .with(as(UserRole.OWNER))
                .param("approval_status", "PENDING")
                .param("reservation_status", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_count").value(0));
    verify(service)
        .list(
            9L,
            3L,
            1,
            20,
            com.togethermarket.reservation.entity.ApprovalStatus.PENDING,
            com.togethermarket.reservation.entity.ReservationStatus.PENDING);
    mvc.perform(
            get("/owner/stores/3/orders")
                .with(as(UserRole.OWNER))
                .param("approval_status", "UNKNOWN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
