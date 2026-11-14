package com.togethermarket.demo;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs after context initialization (including Flyway), before application readiness. */
@Component
@Profile("(local | demo) & !prod & !production & !coordinate-backfill")
@ConditionalOnProperty(name = "demo.catalog.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CsvDemoInitializer implements ApplicationRunner {
  private static final System.Logger LOG = System.getLogger(CsvDemoInitializer.class.getName());
  private final DataSource dataSource;

  public CsvDemoInitializer(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    var bundle = CsvDemoBundle.load();
    var changes = new CsvDemoSeeder(dataSource).seed(bundle);
    LOG.log(
        System.Logger.Level.INFO,
        "CSV_DEMO_INITIALIZED stores={0}, products={1}, inserted={2}",
        bundle.manifest().stores(),
        bundle.manifest().products(),
        changes);
  }
}
