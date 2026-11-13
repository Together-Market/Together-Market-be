package com.togethermarket.global.auth;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.user.entity.UserRole;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokenRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  OwnerAccess ownerAccess() {
    return request -> {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !(auth.getPrincipal() instanceof LoginUser user))
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
      if (user.role() != UserRole.OWNER)
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "사장님 권한이 필요합니다.");
      return user.userId();
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, SecurityContextRepository contexts, CsrfTokenRepository tokens)
      throws Exception {
    // Plain header tokens are intentional: the JSON endpoint delivers the session token.
    http.csrf(
        c ->
            c.csrfTokenRepository(tokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    http.securityContext(c -> c.securityContextRepository(contexts));
    http.requestCache(c -> c.disable());
    http.formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable());
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/auth/csrf", "/auth/login", "/auth/logout")
                .permitAll()
                .requestMatchers("/owner/**")
                .hasRole("OWNER")
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint((q, s, x) -> error(s, 401, "UNAUTHORIZED", "로그인이 필요합니다."))
                .accessDeniedHandler(
                    (q, s, x) -> {
                      if (x instanceof CsrfException)
                        error(s, 403, "CSRF_INVALID", "CSRF 토큰을 확인해 주세요.");
                      else error(s, 403, "FORBIDDEN", "접근 권한이 없습니다.");
                    }));
    return http.build();
  }

  private static void error(HttpServletResponse response, int status, String code, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"errors\":[]}");
  }
}
