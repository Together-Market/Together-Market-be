package com.togethermarket.recommendation.config;

import com.togethermarket.product.entity.SaleStatus;
import com.togethermarket.product.repository.*;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.recommendation.service.customer.*;
import com.togethermarket.recommendation.service.purchase.PurchaseInputException;
import com.togethermarket.store.repository.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.customer-read.enabled", havingValue = "true")
public class CustomerReadConfiguration {
  @Bean
  @ConditionalOnMissingBean(CustomerReadAccess.class)
  CustomerReadAccess sessionCustomerReadAccess() {
    return request -> {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (authentication == null
          || !(authentication.getPrincipal()
              instanceof com.togethermarket.global.auth.LoginUser user))
        throw new PurchaseInputException("UNAUTHORIZED", "로그인이 필요합니다.", List.of());
      if (user.role() != com.togethermarket.user.entity.UserRole.CUSTOMER)
        throw new PurchaseInputException("FORBIDDEN", "고객 권한이 필요합니다.", List.of());
      return user.userId();
    };
  }

  @Bean
  CustomerReadPolicy customerReadPolicy(Environment env) {
    var statuses =
        Arrays.stream(
                env.getRequiredProperty("recommendation.customer-read.visible-sale-statuses")
                    .split(","))
            .map(String::trim)
            .map(SaleStatus::valueOf)
            .collect(Collectors.toSet());
    boolean required =
        switch (env.getRequiredProperty("recommendation.customer-read.coordinate-policy")) {
          // Keep the previous configuration spelling as an alias for existing deployments.
          case "DETAIL_NULL_MAP_NULL", "DETAIL_NULL_MAP_ERROR" -> false;
          case "ALL_COORDINATES_REQUIRED" -> true;
          default -> throw new IllegalArgumentException("Choose a supported coordinate policy");
        };
    return new CustomerReadPolicy(statuses, required);
  }

  @Bean
  CustomerReadService customerReadService(
      ProductRepository products,
      ProductPurposeTagRepository tags,
      StoreRepository stores,
      StoreHourRepository hours,
      RecommendationCandidateRepository candidates,
      RecommendationItemRepository items,
      PurchaseRequestRepository purchases,
      CustomerReadPolicy policy) {
    return new CustomerReadService(
        products, tags, stores, hours, candidates, items, purchases, policy);
  }
}
