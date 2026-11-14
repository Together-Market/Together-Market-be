package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.config.DemoStoreRegistrationCommand;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DemoStoreRegistrationCommandTest {
  @TempDir Path dir;

  @Test
  void readsOnlySelectedIdsAndIgnoresCommentsAndBom() throws Exception {
    Path file = dir.resolve("selected.ids");
    Files.writeString(file, "\uFEFF# selected\nMA001\n\n MA002 \n");
    assertEquals(List.of("MA001", "MA002"), DemoStoreRegistrationCommand.readIds(file));
  }

  @Test
  void rejectsWrongFileTypeBeforeReadingIt() {
    assertThrows(
        PublicDataException.class, () -> DemoStoreRegistrationCommand.readIds(dir.resolve(".env")));
  }

  @Test
  void collectionProfileCannotRunAlongsideRegistration() {
    var env = new MockEnvironment();
    env.setActiveProfiles("demo-stores", "public-data");
    var service = mock(DemoStoreRegistrationService.class);
    assertThrows(
        PublicDataException.class, () -> new DemoStoreRegistrationCommand(env, service).run(null));
    verifyNoInteractions(service);
  }
}
