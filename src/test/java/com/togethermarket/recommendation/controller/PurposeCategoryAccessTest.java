package com.togethermarket.recommendation.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.*;
import com.togethermarket.recommendation.config.CustomerReadConfiguration;
import com.togethermarket.recommendation.dto.response.PurposeCategoriesResponse;
import com.togethermarket.recommendation.service.customer.CustomerReadService;
import com.togethermarket.recommendation.service.persistence.JpaPurchaseCreationService;
import com.togethermarket.recommendation.service.purchase.*;
import com.togethermarket.user.entity.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
    controllers = PurchaseController.class,
    properties = {
      "recommendation.api.enabled=true",
      "recommendation.customer-read.enabled=true",
      "recommendation.customer-read.visible-sale-statuses=ON_SALE",
      "recommendation.customer-read.coordinate-policy=DETAIL_NULL_MAP_NULL"
    })
@Import({
  SecurityConfiguration.class,
  CorsConfiguration.class,
  CustomerReadConfiguration.class,
  PurposeCategoryAccessTest.TestSecurity.class
})
class PurposeCategoryAccessTest {
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
  static class TestSecurity {}

  @Autowired MockMvc mvc;
  @MockitoBean PurposeCategoryService categories;
  @MockitoBean PurposeQuestionService questions;
  @MockitoBean JpaPurchaseCreationService creation;
  @MockitoBean PurchaseRequestQueryService queries;
  @MockitoBean CustomerReadService customerReads;

  private RequestPostProcessor as(UserRole role) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new LoginUser(1L, role),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }

  @ParameterizedTest
  @EnumSource(UserRole.class)
  void bothRolesReadSameCategoryTreeWithoutCsrf(UserRole role) throws Exception {
    when(categories.categories())
        .thenReturn(
            new PurposeCategoriesResponse(
                List.of(
                    new PurposeCategoriesResponse.Category(
                        1L, "기념일", List.of(new PurposeCategoriesResponse.Child(11L, 1L, "생일"))))));
    mvc.perform(get("/purpose-categories").with(as(role)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value("1"))
        .andExpect(jsonPath("$.data.items[0].children[0].id").value("11"))
        .andExpect(jsonPath("$.data.items[0].children[0].parent_id").value("1"));
  }

  @Test
  void anonymousCannotReadCategories() throws Exception {
    mvc.perform(get("/purpose-categories"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    verifyNoInteractions(categories);
  }

  @Test
  void ownerStillCannotUseCustomerPurchaseEndpoints() throws Exception {
    mvc.perform(get("/purpose-categories/11/questions").with(as(UserRole.OWNER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/purchase-requests/1").with(as(UserRole.OWNER)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/purchase-requests")
                .with(as(UserRole.OWNER))
                .with(csrf())
                .contentType("application/json")
                .content(
                    """
          {"purpose_category_id":"11","budget_amount":50000,
           "preferred_pickup_at":"2026-11-20T15:00:00+09:00","answers":[]}
          """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verifyNoInteractions(questions, queries, creation);
  }

  @Test
  void customerStillReachesPurchaseReadServices() throws Exception {
    mvc.perform(get("/purpose-categories/11/questions").with(as(UserRole.CUSTOMER)))
        .andExpect(status().isOk());
    mvc.perform(get("/purchase-requests/1").with(as(UserRole.CUSTOMER))).andExpect(status().isOk());
    verify(questions).questions("11");
    verify(queries).get("1", 1L);
  }
}
