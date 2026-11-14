package com.togethermarket.store.service.geocoding;

import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.CoordinateBackfillRepository;
import com.togethermarket.store.repository.CoordinateBackfillRepository.PublicCoordinate;
import com.togethermarket.store.service.publicdata.PublicStoreData;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Explicit one-shot maintenance, never invoked by a customer read or ordinary server startup. */
@Service
public class CoordinateBackfillService {
  public record Options(
      boolean dryRun,
      long afterStoreId,
      long throughStoreId,
      int limit,
      boolean previewKakao,
      int maxKakaoCalls,
      long delayMillis) {
    public Options {
      if (afterStoreId < 0
          || throughStoreId <= afterStoreId
          || limit < 1
          || limit > 1000
          || maxKakaoCalls < 0
          || maxKakaoCalls > 100
          || delayMillis < 100
          || delayMillis > 10000) throw new IllegalArgumentException("좌표 보완 실행 범위를 확인해 주세요.");
      if (previewKakao && !dryRun) throw new IllegalArgumentException("카카오는 미리보기에서만 사용할 수 있습니다.");
    }
  }

  public record Row(
      String storeId,
      String status,
      String source,
      String reason,
      BigDecimal latitude,
      BigDecimal longitude) {}

  public record Report(boolean dryRun, String lastStoreId, int kakaoCalls, List<Row> rows) {
    public Report {
      rows = List.copyOf(rows);
    }
  }

  @FunctionalInterface
  interface Pause {
    void sleep(long millis) throws InterruptedException;
  }

  private final CoordinateBackfillRepository repository;
  private final AddressGeocoder geocoder;
  private final Pause pause;

  @org.springframework.beans.factory.annotation.Autowired
  public CoordinateBackfillService(
      CoordinateBackfillRepository repository, AddressGeocoder geocoder) {
    this(repository, geocoder, Thread::sleep);
  }

  CoordinateBackfillService(
      CoordinateBackfillRepository repository, AddressGeocoder geocoder, Pause pause) {
    this.repository = repository;
    this.geocoder = geocoder;
    this.pause = pause;
  }

  public Report run(Options options) {
    var selected =
        repository.missing(options.afterStoreId(), options.throughStoreId(), options.limit());
    List<Row> rows = new ArrayList<>();
    // Store only status codes for deduplication, never Kakao response coordinates or bodies.
    Map<String, String> previewOutcomes = new HashMap<>();
    int calls = 0;
    boolean externalStopped = false;
    long last = options.afterStoreId();
    for (var store : selected) {
      last = store.id();
      String id = Long.toString(store.id());
      if (store.latitude() != null && store.longitude() != null) {
        rows.add(row(id, "SKIPPED", "NONE", "COORDINATES_PRESENT"));
        continue;
      }
      try {
        String address = PublicStoreData.normalizedAddress(store.address());
        List<PublicCoordinate> usable =
            repository.linked(store.id()).stream()
                .filter(
                    p ->
                        !address.isBlank()
                            && address.equals(PublicStoreData.normalizedAddress(p.address())))
                .filter(p -> Coordinates.usable(p.latitude(), p.longitude()))
                .toList();
        var pairs =
            usable.stream()
                .map(p -> new Coordinates(p.latitude(), p.longitude()))
                .distinct()
                .toList();
        if (pairs.size() > 1) {
          rows.add(row(id, "FAILED", "PUBLIC_DATA", "PUBLIC_COORDINATES_CONFLICT"));
          continue;
        }
        if (pairs.size() == 1) {
          var pair = pairs.getFirst();
          String status =
              options.dryRun()
                  ? "WOULD_UPDATE"
                  : repository.fill(store, usable.getFirst(), pair)
                      ? "UPDATED"
                      : "SKIPPED_CONCURRENT_CHANGE";
          rows.add(new Row(id, status, "PUBLIC_DATA", "NONE", pair.latitude(), pair.longitude()));
          continue;
        }
        if (!options.previewKakao()) {
          rows.add(row(id, "FAILED", "NONE", "NO_USABLE_PUBLIC_COORDINATES"));
          continue;
        }
        String outcome = previewOutcomes.get(address);
        if (outcome == null) {
          if (externalStopped || calls >= options.maxKakaoCalls()) {
            rows.add(
                row(
                    id,
                    "FAILED",
                    "KAKAO",
                    externalStopped ? "KAKAO_CALLS_STOPPED" : "KAKAO_CALL_LIMIT"));
            continue;
          }
          if (calls > 0) pause.sleep(options.delayMillis());
          calls++;
          try {
            geocoder.lookup(store.address());
            outcome = "KAKAO_STORAGE_NOT_ALLOWED";
          } catch (OwnerStoreException ex) {
            outcome = ex.code();
            // Stop subsequent calls on quota, configuration or infrastructure failures; no retries.
            externalStopped = ex.status().is5xxServerError();
          }
          previewOutcomes.put(address, outcome);
        }
        rows.add(
            row(
                id,
                outcome.equals("KAKAO_STORAGE_NOT_ALLOWED") ? "PREVIEW_ONLY" : "FAILED",
                "KAKAO",
                outcome));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        rows.add(row(id, "FAILED", "NONE", "INTERRUPTED"));
        break;
      } catch (DataAccessException ex) {
        rows.add(row(id, "FAILED", "NONE", "DATABASE_ERROR"));
        break;
      }
    }
    return new Report(options.dryRun(), Long.toString(last), calls, rows);
  }

  private static Row row(String id, String status, String source, String reason) {
    return new Row(id, status, source, reason, null, null);
  }
}
