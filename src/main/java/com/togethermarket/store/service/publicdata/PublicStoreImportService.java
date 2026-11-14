package com.togethermarket.store.service.publicdata;

import com.togethermarket.store.repository.PublicStoreImportRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicStoreImportService {
  private final PublicStoreImportRepository repository;

  public PublicStoreImportService(PublicStoreImportRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public int savePage(List<PublicStoreData> rows, LocalDateTime collectedAt) {
    for (PublicStoreData row : rows) {
      List<Long> legacyIds = repository.findLegacyIds(row);
      if (legacyIds.size() > 1) {
        throw new PublicDataException("기존 공공데이터 중복 레코드를 먼저 정리해 주세요.");
      }
      if (legacyIds.size() == 1) {
        repository.assignCollectionKey(legacyIds.getFirst(), row.key());
      }
      repository.upsertRecord(row, collectedAt);
    }
    return rows.size();
  }
}
