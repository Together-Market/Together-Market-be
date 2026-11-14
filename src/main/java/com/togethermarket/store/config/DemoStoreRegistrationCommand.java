package com.togethermarket.store.config;

import com.togethermarket.store.service.publicdata.DemoStoreRegistrationService;
import com.togethermarket.store.service.publicdata.PublicDataException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile("demo-stores & !coordinate-backfill")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoStoreRegistrationCommand implements ApplicationRunner {
  private final Environment env;
  private final DemoStoreRegistrationService service;

  public DemoStoreRegistrationCommand(Environment env, DemoStoreRegistrationService service) {
    this.env = env;
    this.service = service;
  }

  public static List<String> readIds(Path path) throws IOException {
    if (!path.getFileName().toString().endsWith(".ids"))
      throw new PublicDataException("선정한 원천 ID만 담은 .ids 파일을 지정하세요.");
    if (Files.size(path) > 1100000) throw new PublicDataException("ID 파일이 너무 큽니다.");
    return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
        .map(line -> line.replace("\uFEFF", "").strip())
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .toList();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (env.acceptsProfiles(Profiles.of("public-data")))
      throw new PublicDataException("demo-stores와 public-data 프로필을 함께 사용하지 마세요.");
    try {
      var ids = readIds(Path.of(env.getRequiredProperty("demo-stores.external-ids-file")));
      var result =
          service.register(
              env.getRequiredProperty("demo-stores.owner-id", Long.class),
              ids,
              env.getProperty("demo-stores.dry-run", Boolean.class, true),
              env.getProperty("demo-stores.allow-missing-phone", Boolean.class, false));
      System.out.printf(
          "demo-stores selected=%d create=%d connectExisting=%d alreadyConnected=%d dryRun=%s%n",
          result.selected(),
          result.wouldCreate(),
          result.wouldConnect(),
          result.alreadyConnected(),
          result.dryRun());
    } catch (PublicDataException ex) {
      throw ex;
    } catch (IOException | RuntimeException ex) {
      throw new PublicDataException("데모 점포 등록이 실패했습니다. 파일·설정·DB를 확인하세요. 저장 작업은 전체 취소됩니다.");
    }
  }
}
