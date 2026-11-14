package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.config.PublicStoreCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublicStoreCommandTest {
  @Test
  void securityConfigurationStartsWithoutWebServer() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(com.togethermarket.global.auth.SecurityConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(
                  context
                      .getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)
                      .isEmpty());
            });
  }

  @Test
  void dryRunDoesNotWrite() {
    var env =
        new MockEnvironment()
            .withProperty("public-data.source", "YPAY")
            .withProperty("YPAY_SERVICE_KEY", "test");
    var client = mock(PublicStoreClient.class);
    var importer = mock(PublicStoreImportService.class);
    var matches = mock(PublicStoreMatchService.class);
    when(client.fetch("YPAY", "test", 1, 5, "41463"))
        .thenReturn(new PublicStoreParser.Page(1, 5, 0, List.of(), 0));
    new PublicStoreCommand(env, client, importer, matches).run(null);
    verifyNoInteractions(importer, matches);
  }

  @Test
  void storingRejectsNonKstTimeZone() {
    var env =
        new MockEnvironment()
            .withProperty("public-data.source", "YPAY")
            .withProperty("public-data.dry-run", "false")
            .withProperty("public-data.storage-zone", "UTC");
    var client = mock(PublicStoreClient.class);
    assertThrows(
        PublicDataException.class,
        () ->
            new PublicStoreCommand(
                    env,
                    client,
                    mock(PublicStoreImportService.class),
                    mock(PublicStoreMatchService.class))
                .run(null));
    verifyNoInteractions(client);
  }
}
