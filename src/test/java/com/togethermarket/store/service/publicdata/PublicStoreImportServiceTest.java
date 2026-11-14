package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.repository.PublicStoreImportRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicStoreImportServiceTest {
  private final PublicStoreImportRepository repository = mock(PublicStoreImportRepository.class);
  private final PublicStoreImportService service = new PublicStoreImportService(repository);
  private final PublicStoreData row =
      new PublicStoreData("YPAY", null, "1", "카페", null, "카페", "경기도 용인시 예시로 1", null, null);
  private final LocalDateTime seen = LocalDateTime.of(2026, 11, 14, 12, 0);

  @Test
  void uniqueLegacyRecordGetsKeyBeforeUpsert() {
    when(repository.findLegacyIds(row)).thenReturn(List.of(3L));
    assertEquals(1, service.savePage(List.of(row), seen));
    var order = inOrder(repository);
    order.verify(repository).findLegacyIds(row);
    order.verify(repository).assignCollectionKey(3L, row.key());
    order.verify(repository).upsertRecord(row, seen);
  }

  @Test
  void ambiguousLegacyStopsWithoutAssigningOrSaving() {
    when(repository.findLegacyIds(row)).thenReturn(List.of(3L, 4L));
    assertThrows(PublicDataException.class, () -> service.savePage(List.of(row), seen));
    verify(repository).findLegacyIds(row);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void newRecordDoesNotAssignLegacyKey() {
    when(repository.findLegacyIds(row)).thenReturn(List.of());
    service.savePage(List.of(row), seen);
    verify(repository).upsertRecord(row, seen);
    verify(repository, never()).assignCollectionKey(anyLong(), anyString());
  }
}
