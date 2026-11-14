package com.togethermarket.store.config;

import com.togethermarket.store.service.publicdata.*;
import java.time.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("public-data")
public class PublicStoreCommand implements ApplicationRunner {
  private final Environment env;
  private final PublicStoreClient client;
  private final PublicStoreImportService importer;
  private final PublicStoreMatchService matches;

  public PublicStoreCommand(
      Environment env,
      PublicStoreClient client,
      PublicStoreImportService importer,
      PublicStoreMatchService matches) {
    this.env = env;
    this.client = client;
    this.importer = importer;
    this.matches = matches;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      execute();
    } catch (PublicDataException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new PublicDataException("공공데이터 작업이 실패했습니다. 설정과 DB 상태를 확인해 주세요. 이전 완료 페이지는 유지됩니다.");
    }
  }

  private void execute() {
    String op = env.getProperty("public-data.operation", "collect");
    if (!op.equals("collect")) {
      Long record = env.getRequiredProperty("public-data.record-id", Long.class);
      boolean confirmed = env.getProperty("public-data.confirm", Boolean.class, false);
      switch (op) {
        case "candidates" ->
            matches
                .candidates(record)
                .forEach(
                    c ->
                        System.out.println(
                            "candidate=" + c.storeId() + ", " + c.name() + ", " + c.address()));
        case "match" ->
            matches.confirm(
                record, env.getRequiredProperty("public-data.store-id", Long.class), confirmed);
        case "unmatch" -> matches.unmatch(record, confirmed);
        default -> throw new PublicDataException("지원하지 않는 실행 작업입니다.");
      }
      return;
    }
    String source = env.getRequiredProperty("public-data.source");
    String key = env.getProperty(source.equals("SBIZ") ? "SBIZ_SERVICE_KEY" : "YPAY_SERVICE_KEY");
    int start = env.getProperty("public-data.start-page", Integer.class, 1),
        limit = env.getProperty("public-data.max-pages", Integer.class, 1),
        size = env.getProperty("public-data.page-size", Integer.class, 5);
    if (start < 1 || limit < 1 || limit > 10000 || ((long) start + limit) > Integer.MAX_VALUE)
      throw new PublicDataException("페이지 범위를 확인해 주세요.");
    boolean dry = env.getProperty("public-data.dry-run", Boolean.class, true);
    // Shared DB DATETIME policy: KST.
    ZoneId zone = ZoneId.of(env.getProperty("public-data.storage-zone", "Asia/Seoul"));
    if (!zone.equals(ZoneId.of("Asia/Seoul")))
      throw new PublicDataException("수집일시 저장 시간대는 Asia/Seoul이어야 합니다.");
    for (int p = start; p < start + limit; p++) {
      var page =
          client.fetch(source, key, p, size, env.getProperty("public-data.district", "41463"));
      if (!dry) importer.savePage(page.items(), LocalDateTime.now(zone));
      System.out.printf(
          "source=%s page=%d valid=%d skipped=%d total=%d dryRun=%s%n",
          source, p, page.items().size(), page.skipped(), page.total(), dry);
      if ((long) p * size >= page.total()) break;
      try {
        Thread.sleep(200);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new PublicDataException("수집이 중단됐습니다.");
      }
    }
  }
}
