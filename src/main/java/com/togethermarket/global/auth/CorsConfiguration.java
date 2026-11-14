package com.togethermarket.global.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/** Explicit origin allowlist for the existing session/CSRF API. Empty means same-origin only. */
@Configuration(proxyBeanMethods = false)
public class CorsConfiguration {
  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource(Environment environment) {
    String configured = environment.getProperty("app.cors.allowed-origins", "").strip();
    List<String> origins =
        configured.isEmpty()
            ? List.of()
            : Arrays.stream(configured.split(",", -1))
                .map(String::strip)
                .map(CorsConfiguration::origin)
                .distinct()
                .toList();
    var configuration = new org.springframework.web.cors.CorsConfiguration();
    configuration.setAllowedOrigins(origins);
    configuration.setAllowCredentials(true);
    configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Accept", "Content-Type", "X-CSRF-TOKEN", "Idempotency-Key"));
    configuration.setMaxAge(600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private static String origin(String value) {
    try {
      URI uri = URI.create(value);
      if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "CORS_ALLOWED_ORIGINS에는 경로·와일드카드 없는 http/https 출처를 쉼표로 구분해 입력하세요.");
    }
  }

  // Created inside the security chain, not a servlet Filter bean registered a second time.
  static CorsFilter filter(UrlBasedCorsConfigurationSource source) {
    var filter = new CorsFilter(source);
    filter.setCorsProcessor(
        new DefaultCorsProcessor() {
          @Override
          protected void rejectRequest(ServerHttpResponse response) throws IOException {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            response
                .getHeaders()
                .setContentType(MediaType.parseMediaType("application/json;charset=UTF-8"));
            response
                .getBody()
                .write(
                    "{\"code\":\"CORS_REJECTED\",\"message\":\"허용되지 않은 교차 출처 요청입니다.\",\"errors\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
            response.flush();
          }
        });
    return filter;
  }
}
