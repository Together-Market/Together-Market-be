package com.togethermarket.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CsvDemoActivationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(CsvDemoInitializer.class)
          .withBean(DataSource.class, () -> mock(DataSource.class));

  @Test
  void localAndDemoRequireExplicitOptIn() {
    for (String profile : new String[] {"local", "demo", "demo,csv-demo"}) {
      runner
          .withPropertyValues("spring.profiles.active=" + profile)
          .run(context -> assertThat(context).doesNotHaveBean(CsvDemoInitializer.class));
      runner
          .withPropertyValues("spring.profiles.active=" + profile, "demo.catalog.enabled=true")
          .run(context -> assertThat(context).hasSingleBean(CsvDemoInitializer.class));
    }
    runner
        .withPropertyValues("spring.profiles.default=local")
        .run(context -> assertThat(context).doesNotHaveBean(CsvDemoInitializer.class));
  }

  @Test
  void productionAndExplicitDisableNeverRegisterTheInitializer() {
    for (String profile :
        new String[] {
          "prod", "production", "local,prod", "demo,production", "test", "demo,coordinate-backfill"
        }) {
      runner
          .withPropertyValues("spring.profiles.active=" + profile, "demo.catalog.enabled=true")
          .run(context -> assertThat(context).doesNotHaveBean(CsvDemoInitializer.class));
    }
    runner
        .withPropertyValues("spring.profiles.active=local", "demo.catalog.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(CsvDemoInitializer.class));
  }
}
