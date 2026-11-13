package com.togethermarket;

import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.store.repository.StoreHourRepository;
import com.togethermarket.store.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** DB-free wiring smoke test; real persistence is checked in DatabaseSchemaIntegrationTest. */
@SpringBootTest
class TogetherMarketApplicationTests {
  @MockitoBean ProductRepository products;
  @MockitoBean StoreRepository stores;
  @MockitoBean StoreHourRepository hours;

  @Test
  void contextLoads() {}
}
