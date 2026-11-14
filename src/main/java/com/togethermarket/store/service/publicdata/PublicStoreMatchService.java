package com.togethermarket.store.service.publicdata;

import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PublicStoreMatchService {
  private final PublicStoreRecordRepository records;
  private final StoreRepository stores;

  public PublicStoreMatchService(PublicStoreRecordRepository records, StoreRepository stores) {
    this.records = records;
    this.stores = stores;
  }

  public record Candidate(Long storeId, String name, String address) {}

  public List<Candidate> candidates(Long recordId) {
    var row =
        records.findById(recordId).orElseThrow(() -> new PublicDataException("공공데이터 레코드가 없습니다."));
    List<Candidate> result = new ArrayList<>();
    int page = 0;
    org.springframework.data.domain.Page<Store> batch;
    do {
      batch =
          stores.findAll(
              PageRequest.of(page++, 100, org.springframework.data.domain.Sort.by("id")));
      for (Store store : batch) {
        if (same(row.getName(), row.getAddress(), store))
          result.add(new Candidate(store.getId(), store.getName(), store.getAddress()));
      }
    } while (batch.hasNext());
    return List.copyOf(result);
  }

  private boolean same(String name, String address, Store store) {
    return !PublicStoreData.normalize(name).isEmpty()
        && !PublicStoreData.normalizedAddress(address).isEmpty()
        && PublicStoreData.normalize(name).equals(PublicStoreData.normalize(store.getName()))
        && PublicStoreData.normalizedAddress(address)
            .equals(PublicStoreData.normalizedAddress(store.getAddress()));
  }

  @Transactional
  public void confirm(Long recordId, Long storeId, boolean confirmed) {
    if (!confirmed) throw new PublicDataException("명시적인 연결 확인이 필요합니다.");
    var row =
        records
            .findForUpdate(recordId)
            .orElseThrow(() -> new PublicDataException("공공데이터 레코드가 없습니다."));
    var store = stores.findById(storeId).orElseThrow(() -> new PublicDataException("참여 점포가 없습니다."));
    if (!same(row.getName(), row.getAddress(), store))
      throw new PublicDataException("점포명과 주소가 일치하지 않습니다. 자동 연결하지 않습니다.");
    if (row.getMatchedStoreId() != null && !row.getMatchedStoreId().equals(storeId))
      throw new PublicDataException("기존 연결을 먼저 명시적으로 해제해 주세요.");
    row.match(storeId);
  }

  @Transactional
  public void unmatch(Long recordId, boolean confirmed) {
    if (!confirmed) throw new PublicDataException("명시적인 해제 확인이 필요합니다.");
    records
        .findForUpdate(recordId)
        .orElseThrow(() -> new PublicDataException("공공데이터 레코드가 없습니다."))
        .match(null);
  }
}
