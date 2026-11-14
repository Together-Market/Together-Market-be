package com.togethermarket.store.service.geocoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.store.exception.OwnerStoreException;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class KakaoAddressGeocoderTest {
  final HttpClient http = mock(HttpClient.class);
  final KakaoAddressGeocoder client =
      new KakaoAddressGeocoder(
          http, new JsonMapper(), "test-only-placeholder", Duration.ofSeconds(3));
  static final String FOUND =
      """
      {"meta":{"total_count":1},"documents":[{"address_type":"ROAD_ADDR","x":"127.151281349","y":"37.275853849"}]}
      """;

  @SuppressWarnings("unchecked")
  void response(int status, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  void fails(String code) {
    assertThatThrownBy(() -> client.lookup("경기도 용인시 도로 1"))
        .isInstanceOfSatisfying(
            OwnerStoreException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.getMessage())
                  .doesNotContain(
                      "test-only-placeholder", "Authorization", "provider-private-body");
              assertThat(e.getCause()).isNull();
            });
  }

  @Test
  void encodesAddressAndMapsXYWithoutLosingPrecision() throws Exception {
    response(200, FOUND);
    var pair = client.lookup("경기도 용인시 도로 1 & 2");
    assertThat(pair.latitude()).isEqualByComparingTo("37.2758538");
    assertThat(pair.longitude()).isEqualByComparingTo("127.1512813");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().getScheme()).isEqualTo("https");
    assertThat(request.getValue().uri().getHost()).isEqualTo("dapi.kakao.com");
    assertThat(request.getValue().uri().getRawQuery())
        .contains("%26", "analyze_type=exact", "size=2")
        .doesNotContain("test-only-placeholder");
    assertThat(request.getValue().headers().firstValue("Authorization"))
        .contains("KakaoAK test-only-placeholder");
    assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
  }

  @ParameterizedTest
  @CsvSource({
    "401,GEOCODE_NOT_CONFIGURED",
    "403,GEOCODE_NOT_CONFIGURED",
    "429,GEOCODE_RATE_LIMITED",
    "500,GEOCODE_UPSTREAM_ERROR",
    "302,GEOCODE_UPSTREAM_ERROR",
    "400,GEOCODE_UPSTREAM_ERROR"
  })
  void upstreamErrorsAreSanitizedAndNotRetried(int status, String code) throws Exception {
    response(status, "provider-private-body test-only-placeholder");
    fails(code);
    verify(http, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void emptyAmbiguousAndBroadAddressesHaveDistinctErrors() throws Exception {
    response(200, "{\"meta\":{\"total_count\":0},\"documents\":[]}");
    fails("ADDRESS_NOT_FOUND");
    response(200, FOUND.replace("total_count\":1", "total_count\":20"));
    fails("ADDRESS_AMBIGUOUS");
    response(200, FOUND.replace("ROAD_ADDR", "ROAD"));
    fails("ADDRESS_INCOMPLETE");
  }

  @Test
  void brokenResponsesAndInvalidCoordinatesNeverBecomeZero() throws Exception {
    for (String body :
        new String[] {
          "broken",
          "null",
          "{}",
          FOUND.replace("37.275853849", "91"),
          FOUND.replace("37.275853849", "NaN"),
          FOUND.replace("37.275853849", "0").replace("127.151281349", "0"),
          FOUND.replace("\"x\"", "\"other\""),
          FOUND.replace("total_count\":1", "total_count\":0")
        }) {
      response(200, body);
      fails("GEOCODE_UPSTREAM_ERROR");
    }
  }

  @Test
  void missingKeyAndInvalidAddressNeverCallNetwork() {
    var disabled = new KakaoAddressGeocoder(http, new JsonMapper(), "", Duration.ofSeconds(3));
    assertThatThrownBy(() -> disabled.lookup("주소"))
        .isInstanceOfSatisfying(
            OwnerStoreException.class,
            e -> assertThat(e.code()).isEqualTo("GEOCODE_NOT_CONFIGURED"));
    assertThatThrownBy(() -> client.lookup(" ")).isInstanceOf(OwnerStoreException.class);
    verifyNoInteractions(http);
  }

  @Test
  void timeoutAndIoFailureAreSanitized() throws Exception {
    doThrow(new HttpTimeoutException("test-only-placeholder"))
        .when(http)
        .send(any(), any(HttpResponse.BodyHandler.class));
    fails("GEOCODE_TIMEOUT");
    doThrow(new IOException("Authorization test-only-placeholder"))
        .when(http)
        .send(any(), any(HttpResponse.BodyHandler.class));
    fails("GEOCODE_UPSTREAM_ERROR");
  }

  @Test
  void interruptionRestoresFlagAndDropsSensitiveCause() throws Exception {
    doThrow(new InterruptedException("test-only-placeholder"))
        .when(http)
        .send(any(), any(HttpResponse.BodyHandler.class));
    try {
      fails("GEOCODE_UNAVAILABLE");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
