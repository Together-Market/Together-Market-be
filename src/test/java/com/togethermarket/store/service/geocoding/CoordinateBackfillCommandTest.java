package com.togethermarket.store.service.geocoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.config.CoordinateBackfillCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class CoordinateBackfillCommandTest {
  final CoordinateBackfillService service = mock(CoordinateBackfillService.class);

  MockEnvironment environment() {
    var env =
        new MockEnvironment()
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .withProperty("spring.sql.init.mode", "never")
            .withProperty("reservation.expiry-scheduler.enabled", "false");
    env.setActiveProfiles("coordinate-backfill");
    return env;
  }

  @Test
  void defaultsToBoundedDryRunWithoutKakao() {
    when(service.run(any()))
        .thenReturn(new CoordinateBackfillService.Report(true, "0", 0, List.of()));
    new CoordinateBackfillCommand(environment(), service).run(new DefaultApplicationArguments());
    verify(service)
        .run(
            argThat(
                o -> o.dryRun() && !o.previewKakao() && o.limit() == 100 && o.afterStoreId() == 0));
  }

  @Test
  void unsafeProfilesAndModesAreRejectedBeforeCallingService() {
    var env = environment();
    env.setActiveProfiles("coordinate-backfill", "demo");
    assertThatThrownBy(
            () ->
                new CoordinateBackfillCommand(env, service).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalArgumentException.class);
    for (String name :
        List.of(
            "demo.catalog.enabled",
            "spring.flyway.enabled",
            "reservation.expiry-scheduler.enabled",
            "recommendation.execution.enabled")) {
      var invalid = environment().withProperty(name, "true");
      assertThatThrownBy(
              () ->
                  new CoordinateBackfillCommand(invalid, service)
                      .run(new DefaultApplicationArguments()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(service);
  }
}
