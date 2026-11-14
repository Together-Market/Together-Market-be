package com.togethermarket.auth.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.togethermarket.auth.service.AuthService;
import com.togethermarket.global.api.*;
import com.togethermarket.global.auth.LoginUser;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService authService;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository tokens;

  public AuthController(
      AuthService authService, SecurityContextRepository contexts, CsrfTokenRepository tokens) {
    this.authService = authService;
    this.contexts = contexts;
    this.tokens = tokens;
  }

  public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

  public record UserResponse(String id, String email, String name, String role) {}

  public record CsrfResponse(@JsonProperty("csrf_token") String csrfToken) {}

  public record LogoutResponse(@JsonProperty("logged_out") boolean loggedOut) {}

  @GetMapping("/csrf")
  ApiResponse<CsrfResponse> csrf(CsrfToken token) {
    return new ApiResponse<>(new CsrfResponse(token.getToken()));
  }

  @PostMapping("/login")
  ApiResponse<UserResponse> login(
      @Valid @RequestBody LoginRequest input,
      HttpServletRequest request,
      HttpServletResponse response) {
    var u = authService.authenticate(input.email(), input.password());
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new LoginUser(u.getId(), u.getRole()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
    new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response);
    new CsrfAuthenticationStrategy(tokens).onAuthentication(auth, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return new ApiResponse<>(
        new UserResponse(u.getId().toString(), u.getEmail(), u.getName(), u.getRole().name()));
  }

  @GetMapping("/me")
  ApiResponse<UserResponse> me(@AuthenticationPrincipal LoginUser principal) {
    if (principal == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    var u = authService.findCurrentUser(principal.userId());
    return new ApiResponse<>(
        new UserResponse(u.getId().toString(), u.getEmail(), u.getName(), u.getRole().name()));
  }

  @PostMapping("/logout")
  ApiResponse<LogoutResponse> logout(HttpServletRequest request, HttpServletResponse response) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    new CsrfLogoutHandler(tokens).logout(request, response, auth);
    new SecurityContextLogoutHandler().logout(request, response, auth);
    return new ApiResponse<>(new LogoutResponse(true));
  }
}
