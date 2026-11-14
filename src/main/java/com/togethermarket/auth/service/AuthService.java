package com.togethermarket.auth.service;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.user.entity.User;
import com.togethermarket.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final String dummyHash;

  public AuthService(UserRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
    this.dummyHash = passwords.encode("invalid-credentials-placeholder");
  }

  public User authenticate(String email, String password) {
    var user = users.findByEmail(email);
    boolean matches =
        passwords.matches(password, user.map(User::getPasswordHash).orElse(dummyHash));
    if (user.isEmpty() || !matches)
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요.");
    return user.get();
  }

  public User findCurrentUser(Long userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "사용자를 확인할 수 없습니다."));
  }
}
