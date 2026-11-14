package com.togethermarket.recommendation.service.customer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * BE3 integration boundary: BE1 must validate server session and CUSTOMER role before returning ID.
 */
@FunctionalInterface
public interface CustomerReadAccess {
  Long requireCustomer(HttpServletRequest request);
}
