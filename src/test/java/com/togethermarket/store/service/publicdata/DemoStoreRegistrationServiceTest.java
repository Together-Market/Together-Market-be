package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.repository.DemoStoreRegistrationRepository;
import com.togethermarket.store.repository.DemoStoreRegistrationRepository.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoStoreRegistrationServiceTest {
  final DemoStoreRegistrationRepository repository = mock(DemoStoreRegistrationRepository.class);
  final DemoStoreRegistrationService service = new DemoStoreRegistrationService(repository);

  SourceRow row(String id, String name, Long matched) {
    return new SourceRow(
        1L,
        id,
        name,
        "카페",
        "경기도 용인시 기흥구 예시로 1",
        new BigDecimal("37.32"),
        new BigDecimal("127.11"),
        matched);
  }

  @BeforeEach
  void setup() {
    when(repository.lockOwner(10L)).thenReturn(true);
    when(repository.lockSource("MA001")).thenReturn(List.of(row("MA001", "카페", null)));
    when(repository.create(eq(10L), any())).thenReturn(20L);
    when(repository.connect(1L, 20L)).thenReturn(1);
  }

  @Test
  void dryRunDoesNotInsertOrConnect() {
    var result = service.register(10L, List.of("MA001"), true, false);
    assertEquals(1, result.wouldCreate());
    verify(repository, never()).create(any(), any());
    verify(repository, never()).connect(any(), any());
  }

  @Test
  void createAndConnectUseTheGeneratedStoreId() {
    var result = service.register(10L, List.of("MA001"), false, true);
    assertEquals(1, result.wouldCreate());
    var order = inOrder(repository);
    order.verify(repository).create(eq(10L), any());
    order.verify(repository).connect(1L, 20L);
  }

  @Test
  void replayPreservesAnAlreadyConnectedStore() {
    when(repository.lockSource("MA001")).thenReturn(List.of(row("MA001", "카페", 20L)));
    when(repository.storeById(20L)).thenReturn(List.of(new ExistingStore(20L, 10L, "수정한 주소")));
    assertEquals(1, service.register(10L, List.of("MA001"), false, false).alreadyConnected());
    verify(repository, never()).create(any(), any());
    verify(repository, never()).connect(any(), any());
  }

  @Test
  void existingSameOwnerStoreIsLinkedWithoutCreation() {
    when(repository.storesWithName("카페"))
        .thenReturn(List.of(new ExistingStore(20L, 10L, "경기 용인시 기흥구 예시로 1")));
    assertEquals(1, service.register(10L, List.of("MA001"), false, false).wouldConnect());
    verify(repository, never()).create(any(), any());
    verify(repository).connect(1L, 20L);
  }

  @Test
  void noOwnerOrDifferentOwnerCannotRegister() {
    assertThrows(
        PublicDataException.class, () -> service.register(11L, List.of("MA001"), false, true));
    when(repository.storesWithName("카페"))
        .thenReturn(List.of(new ExistingStore(20L, 11L, "경기도 용인시 기흥구 예시로 1")));
    assertThrows(
        PublicDataException.class, () -> service.register(10L, List.of("MA001"), false, true));
    verify(repository, never()).create(any(), any());
  }

  @Test
  void invalidLaterRowIsRejectedBeforeAnyInsert() {
    when(repository.lockSource("MA002")).thenReturn(List.of(row("MA002", "가".repeat(151), null)));
    assertThrows(
        PublicDataException.class,
        () -> service.register(10L, List.of("MA001", "MA002"), false, true));
    verify(repository, never()).create(any(), any());
    verify(repository, never()).connect(any(), any());
  }

  @Test
  void missingPhoneRequiresExplicitOptIn() {
    assertThrows(
        PublicDataException.class, () -> service.register(10L, List.of("MA001"), false, false));
    verify(repository, never()).create(any(), any());
  }

  @Test
  void duplicateIdsAndDuplicateIdentitiesAreRejected() {
    assertThrows(
        PublicDataException.class,
        () -> service.register(10L, List.of("MA001", "MA001"), false, true));
    when(repository.lockSource("MA002")).thenReturn(List.of(row("MA002", "카페", null)));
    assertThrows(
        PublicDataException.class,
        () -> service.register(10L, List.of("MA001", "MA002"), false, true));
    verify(repository, never()).create(any(), any());
  }

  @Test
  void changedConnectionAbortsRegistration() {
    when(repository.connect(1L, 20L)).thenReturn(0);
    assertThrows(
        PublicDataException.class, () -> service.register(10L, List.of("MA001"), false, true));
  }
}
