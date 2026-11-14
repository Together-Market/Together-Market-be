package com.togethermarket.store.config;

import com.togethermarket.store.service.geocoding.*;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class GeocodingConfiguration {
  @Bean
  AddressGeocoder addressGeocoder(Environment env, ObjectMapper mapper) {
    return new KakaoAddressGeocoder(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        mapper,
        env.getProperty("kakao.local.rest-api-key", ""),
        Duration.ofSeconds(3));
  }
}
