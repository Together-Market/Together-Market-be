package com.togethermarket.global.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * BE1 integration port: derive ID from authenticated server state, enforce OWNER and CSRF for
 * unsafe methods. Never trust client IDs/roles or headers as authentication.
 */
public interface OwnerAccess {
  Long requireOwner(HttpServletRequest request);
}
