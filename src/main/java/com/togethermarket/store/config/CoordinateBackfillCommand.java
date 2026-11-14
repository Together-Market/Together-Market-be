package com.togethermarket.store.config;

import com.togethermarket.store.service.geocoding.CoordinateBackfillService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile("coordinate-backfill")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoordinateBackfillCommand implements ApplicationRunner {
  private final Environment env;
  private final CoordinateBackfillService service;

  public CoordinateBackfillCommand(Environment env, CoordinateBackfillService service) {
    this.env = env;
    this.service = service;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (env.acceptsProfiles(
            Profiles.of(
                "local", "demo", "public-data", "demo-stores", "csv-demo", "prod", "production"))
        || !"none".equals(env.getProperty("spring.main.web-application-type"))
        || env.getProperty("demo.catalog.enabled", Boolean.class, false)
        || env.getProperty("spring.flyway.enabled", Boolean.class, true)
        || !"validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto"))
        || !"never".equals(env.getProperty("spring.sql.init.mode"))
        || env.getProperty("reservation.expiry-scheduler.enabled", Boolean.class, true)
        || env.getProperty("recommendation.execution.enabled", Boolean.class, false))
      throw new IllegalArgumentException("coordinate-backfill 전용 프로필과 읽기 안전 설정을 사용해 주세요.");
    var options =
        new CoordinateBackfillService.Options(
            env.getProperty("coordinate-backfill.dry-run", Boolean.class, true),
            env.getProperty("coordinate-backfill.after-store-id", Long.class, 0L),
            env.getProperty("coordinate-backfill.through-store-id", Long.class, Long.MAX_VALUE),
            env.getProperty("coordinate-backfill.limit", Integer.class, 100),
            env.getProperty("coordinate-backfill.preview-kakao", Boolean.class, false),
            env.getProperty("coordinate-backfill.max-kakao-calls", Integer.class, 20),
            env.getProperty("coordinate-backfill.delay-millis", Long.class, 250L));
    var report = service.run(options);
    report
        .rows()
        .forEach(
            r ->
                System.out.printf(
                    "coordinate-backfill store_id=%s status=%s source=%s reason=%s latitude=%s longitude=%s%n",
                    r.storeId(), r.status(), r.source(), r.reason(), r.latitude(), r.longitude()));
    System.out.printf(
        "coordinate-backfill dry_run=%s selected=%d last_store_id=%s kakao_calls=%d%n",
        report.dryRun(), report.rows().size(), report.lastStoreId(), report.kakaoCalls());
  }
}
