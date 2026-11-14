package com.togethermarket.global.auth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class CorsConfigurationTest {
  @Test
  void defaultRejectsCrossOriginButKeepsOrdinaryRequestsWorking() throws Exception {
    var source = new CorsConfiguration().corsConfigurationSource(new MockEnvironment());
    var filter = CorsConfiguration.filter(source);
    var request = new MockHttpServletRequest("GET", "/auth/csrf");
    request.addHeader("Origin", "http://localhost:5173");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("CORS_REJECTED");
    assertThat(chain.getRequest()).isNull();
    var ordinary = new MockHttpServletRequest("GET", "/auth/csrf");
    filter.doFilter(ordinary, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isEqualTo(ordinary);
  }

  @Test
  void commaSeparatedOriginsAreTrimmedAndDeduplicated() {
    var env =
        new MockEnvironment()
            .withProperty(
                "app.cors.allowed-origins",
                " http://localhost:5173,https://frontend.example,http://localhost:5173 ");
    var config =
        new CorsConfiguration()
            .corsConfigurationSource(env)
            .getCorsConfiguration(new MockHttpServletRequest("GET", "/auth/csrf"));
    assertThat(config.getAllowedOrigins())
        .containsExactly("http://localhost:5173", "https://frontend.example");
    assertThat(config.getAllowCredentials()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "null",
        "https://*.example",
        "localhost:5173",
        "http://localhost:5173/",
        "http://localhost:5173/path",
        "http://localhost:5173?x=y",
        "http://localhost:5173#fragment",
        "http://user@localhost:5173",
        "http://localhost:70000",
        "http://localhost:5173,"
      })
  void malformedOrWildcardOriginsFailAtStartup(String origin) {
    var env = new MockEnvironment().withProperty("app.cors.allowed-origins", origin);
    assertThatThrownBy(() -> new CorsConfiguration().corsConfigurationSource(env))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CORS_ALLOWED_ORIGINS");
  }
}
