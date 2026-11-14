package com.togethermarket.global.auth;

import com.togethermarket.user.entity.UserRole;
import java.io.Serializable;

public record LoginUser(Long userId, UserRole role) implements Serializable {}
