package com.togethermarket.store.config;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.exception.OwnerStoreException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class OwnerAccessConfiguration {
  @Bean
  @ConditionalOnMissingBean(OwnerAccess.class)
  OwnerAccess unconfiguredOwnerAccess() {
    return request -> {
      throw new OwnerStoreException(
          HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_NOT_CONFIGURED", "인증 연결이 준비되지 않았습니다.");
    };
  }
}
