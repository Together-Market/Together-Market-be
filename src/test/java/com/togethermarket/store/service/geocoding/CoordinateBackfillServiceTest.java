package com.togethermarket.store.service.geocoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.CoordinateBackfillRepository;
import com.togethermarket.store.repository.CoordinateBackfillRepository.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CoordinateBackfillServiceTest {
  final CoordinateBackfillRepository repository = mock(CoordinateBackfillRepository.class);
  final AddressGeocoder geocoder = mock(AddressGeocoder.class);
  final CoordinateBackfillService.Pause pause = mock(CoordinateBackfillService.Pause.class);
  final CoordinateBackfillService service =
      new CoordinateBackfillService(repository, geocoder, pause);
  final BigDecimal lat = new BigDecimal("37.2758538"), lon = new BigDecimal("127.1512813");
  final MissingStore missing = new MissingStore(10, "경기도 용인시 도로 1", new BigDecimal("38"), null);
  final PublicCoordinate source = new PublicCoordinate(20, "경기 용인시 도로 1", lat, lon);

  CoordinateBackfillService.Options options(boolean dryRun, boolean kakao, int calls) {
    return new CoordinateBackfillService.Options(dryRun, 0, 100, 10, kakao, calls, 250);
  }

  void selected(MissingStore... stores) {
    when(repository.missing(0, 100, 10)).thenReturn(List.of(stores));
  }

  @Test
  void dryRunPrioritizesPublicPairAndReplacesBothHalvesWithoutWriting() {
    selected(missing);
    when(repository.linked(10)).thenReturn(List.of(source));
    var report = service.run(options(true, false, 20));
    assertThat(report.rows().getFirst().status()).isEqualTo("WOULD_UPDATE");
    assertThat(report.rows().getFirst().latitude()).isEqualByComparingTo(lat);
    assertThat(report.rows().getFirst().longitude()).isEqualByComparingTo(lon);
    assertThat(report.lastStoreId()).isEqualTo("10");
    verify(repository, never()).fill(any(), any(), any());
    verifyNoInteractions(geocoder);
  }

  @Test
  void applyUsesConditionalWriteAndConcurrentEditIsSkipped() {
    selected(missing);
    when(repository.linked(10)).thenReturn(List.of(source));
    when(repository.fill(eq(missing), eq(source), any())).thenReturn(true, false);
    assertThat(service.run(options(false, false, 20)).rows().getFirst().status())
        .isEqualTo("UPDATED");
    assertThat(service.run(options(false, false, 20)).rows().getFirst().status())
        .isEqualTo("SKIPPED_CONCURRENT_CHANGE");
    verifyNoInteractions(geocoder);
  }

  @Test
  void completedPairsAreNeverOverwrittenEvenIfRepositoryReturnsThem() {
    selected(new MissingStore(10, "주소", lat, lon));
    assertThat(service.run(options(false, false, 20)).rows().getFirst().reason())
        .isEqualTo("COORDINATES_PRESENT");
    verify(repository, never()).linked(anyLong());
    verify(repository, never()).fill(any(), any(), any());
  }

  @Test
  void staleAddressHalfPairAndZeroCoordinatesAreNotCopied() {
    selected(missing);
    when(repository.linked(10))
        .thenReturn(
            List.of(
                new PublicCoordinate(21, "예전 주소", lat, lon),
                new PublicCoordinate(22, missing.address(), lat, null),
                new PublicCoordinate(23, missing.address(), BigDecimal.ZERO, BigDecimal.ZERO)));
    assertThat(service.run(options(false, false, 20)).rows().getFirst().reason())
        .isEqualTo("NO_USABLE_PUBLIC_COORDINATES");
    verify(repository, never()).fill(any(), any(), any());
    verifyNoInteractions(geocoder);
  }

  @Test
  void conflictingSourcesFailButDuplicateEqualSourcesAreSafe() {
    selected(missing);
    when(repository.linked(10))
        .thenReturn(
            List.of(
                source, new PublicCoordinate(21, source.address(), lat.add(BigDecimal.ONE), lon)));
    assertThat(service.run(options(true, false, 20)).rows().getFirst().reason())
        .isEqualTo("PUBLIC_COORDINATES_CONFLICT");
    when(repository.linked(10))
        .thenReturn(List.of(source, new PublicCoordinate(21, source.address(), lat, lon)));
    assertThat(service.run(options(true, false, 20)).rows().getFirst().status())
        .isEqualTo("WOULD_UPDATE");
    verifyNoInteractions(geocoder);
  }

  @Test
  void kakaoPreviewDeduplicatesStatusAndNeverReturnsOrStoresProviderCoordinates() {
    selected(missing, new MissingStore(11, missing.address(), null, null));
    when(geocoder.lookup(missing.address())).thenReturn(new Coordinates(lat, lon));
    var report = service.run(options(true, true, 20));
    assertThat(report.kakaoCalls()).isEqualTo(1);
    assertThat(report.rows())
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo("PREVIEW_ONLY");
              assertThat(r.latitude()).isNull();
              assertThat(r.longitude()).isNull();
            });
    verify(geocoder, times(1)).lookup(anyString());
    verify(repository, never()).fill(any(), any(), any());
  }

  @Test
  void callBudgetAndThrottleBoundTheRun() throws Exception {
    selected(
        missing,
        new MissingStore(11, "다른 주소", null, null),
        new MissingStore(12, "세번째 주소", null, null));
    when(geocoder.lookup(anyString())).thenReturn(new Coordinates(lat, lon));
    var report = service.run(options(true, true, 2));
    assertThat(report.kakaoCalls()).isEqualTo(2);
    assertThat(report.rows().getLast().reason()).isEqualTo("KAKAO_CALL_LIMIT");
    verify(pause).sleep(250);
  }

  @Test
  void quotaFailureStopsSubsequentCallsButStillAllowsPublicPreview() {
    selected(
        missing,
        new MissingStore(11, "다른 주소", null, null),
        new MissingStore(12, missing.address(), null, null));
    when(geocoder.lookup(anyString()))
        .thenThrow(
            new OwnerStoreException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODE_RATE_LIMITED", "제한"));
    when(repository.linked(12)).thenReturn(List.of(source));
    var report = service.run(options(true, true, 20));
    assertThat(report.rows())
        .extracting(r -> r.reason())
        .containsExactly("GEOCODE_RATE_LIMITED", "KAKAO_CALLS_STOPPED", "NONE");
    assertThat(report.kakaoCalls()).isEqualTo(1);
  }

  @Test
  void kakaoApplyCannotBeEnabledAndBadBoundsAreRejected() {
    assertThatThrownBy(() -> options(false, true, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CoordinateBackfillService.Options(true, -1, 100, 10, false, 0, 250))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CoordinateBackfillService.Options(true, 0, 100, 1001, false, 0, 250))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
