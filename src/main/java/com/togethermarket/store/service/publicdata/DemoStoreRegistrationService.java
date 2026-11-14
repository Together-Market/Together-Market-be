package com.togethermarket.store.service.publicdata;

import com.togethermarket.store.repository.DemoStoreRegistrationRepository;
import com.togethermarket.store.repository.DemoStoreRegistrationRepository.SourceRow;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo-stores")
public class DemoStoreRegistrationService {
  private final DemoStoreRegistrationRepository repository;

  public DemoStoreRegistrationService(DemoStoreRegistrationRepository repository) {
    this.repository = repository;
  }

  public record Result(
      int selected, int wouldCreate, int wouldConnect, int alreadyConnected, boolean dryRun) {}

  private record Plan(SourceRow row, Long storeId) {}

  @Transactional
  public Result register(
      Long ownerId, List<String> externalIds, boolean dryRun, boolean allowMissingPhone) {
    if (ownerId == null || ownerId <= 0) fail("사장님 사용자 ID를 지정하세요.");
    if (externalIds == null || externalIds.isEmpty() || externalIds.size() > 5000)
      fail("선정한 소상공인 원천 ID를 1~5000개 지정하세요.");
    if (externalIds.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,200}")))
      fail("원천 ID 파일 형식을 확인하세요.");
    if (new HashSet<>(externalIds).size() != externalIds.size()) fail("원천 ID가 중복됐습니다.");
    if (!repository.lockOwner(ownerId)) fail("해당 OWNER 계정이 없습니다.");
    var plans = new ArrayList<Plan>();
    var identities = new HashSet<String>();
    int already = 0, create = 0, connect = 0;
    // Stable lock order and complete validation before the first INSERT.
    for (String externalId : externalIds.stream().sorted().toList()) {
      var rows = repository.lockSource(externalId);
      if (rows.size() != 1) fail("원천 ID의 SBIZ 레코드가 없거나 중복입니다: " + externalId);
      var row = rows.getFirst();
      if (row.matchedStoreId() != null) {
        var linked = repository.storeById(row.matchedStoreId());
        if (linked.size() != 1 || !linked.getFirst().ownerId().equals(ownerId))
          fail("기존 연결의 점포 또는 소유자를 확인하세요: " + externalId);
        already++;
        continue;
      }
      validate(row);
      String identity =
          PublicStoreData.normalize(row.name())
              + "\0"
              + PublicStoreData.normalizedAddress(row.address());
      if (!identities.add(identity)) fail("동일 이름·주소의 원천 점포를 검토하세요: " + externalId);
      var existing =
          repository.storesWithName(row.name()).stream()
              .filter(
                  store ->
                      PublicStoreData.normalizedAddress(store.address())
                          .equals(PublicStoreData.normalizedAddress(row.address())))
              .toList();
      if (existing.size() > 1) fail("같은 이름·주소의 참여 점포가 여러 개입니다: " + externalId);
      Long storeId = null;
      if (!existing.isEmpty()) {
        if (!existing.getFirst().ownerId().equals(ownerId))
          fail("기존 참여 점포가 다른 사장님 소유입니다: " + externalId);
        storeId = existing.getFirst().id();
        connect++;
      } else create++;
      plans.add(new Plan(row, storeId));
    }
    if (!dryRun && create > 0 && !allowMissingPhone) fail("연락처 미등록(빈 값) 사용을 명시적으로 허용해야 합니다.");
    if (!dryRun) {
      for (Plan plan : plans) {
        Long storeId =
            plan.storeId() != null ? plan.storeId() : repository.create(ownerId, plan.row());
        if (repository.connect(plan.row().id(), storeId) != 1) fail("연결 상태가 변경돼 등록을 취소했습니다.");
      }
    }
    return new Result(externalIds.size(), create, connect, already, dryRun);
  }

  private void validate(SourceRow row) {
    field(row.name(), 150, "점포명", row.externalId());
    field(row.businessType(), 100, "업종", row.externalId());
    field(row.address(), 500, "주소", row.externalId());
    if (row.latitude() == null
        || row.longitude() == null
        || row.latitude().abs().doubleValue() > 90
        || row.longitude().abs().doubleValue() > 180) fail("좌표가 없거나 잘못됐습니다: " + row.externalId());
  }

  private void field(String value, int max, String label, String externalId) {
    if (value == null
        || value.isBlank()
        || value.equalsIgnoreCase("NULL")
        || value.codePointCount(0, value.length()) > max)
      fail(label + "의 필수값·길이를 확인하세요: " + externalId);
  }

  private static void fail(String message) {
    throw new PublicDataException(message);
  }
}
