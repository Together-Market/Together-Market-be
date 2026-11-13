package com.togethermarket.store.config;

import static org.junit.jupiter.api.Assertions.*;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.exception.OwnerStoreException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;

class OwnerAccessConfigurationTest {
  @Test
  void missingAdapterFailsClosed() {
    try (var context = new AnnotationConfigApplicationContext(OwnerAccessConfiguration.class)) {
      var ex =
          assertThrows(
              OwnerStoreException.class,
              () -> context.getBean(OwnerAccess.class).requireOwner(null));
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
    }
  }

  @Test
  void existingAdapterIsUsed() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(OwnerAccess.class, () -> request -> 2L);
      context.register(OwnerAccessConfiguration.class);
      context.refresh();
      assertEquals(2L, context.getBean(OwnerAccess.class).requireOwner(null));
      assertEquals(1, context.getBeansOfType(OwnerAccess.class).size());
    }
  }
}
