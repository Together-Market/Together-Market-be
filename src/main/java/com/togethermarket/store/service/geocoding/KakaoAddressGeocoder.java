package com.togethermarket.store.service.geocoding;

import com.togethermarket.store.exception.OwnerStoreException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** Fixed HTTPS host, bounded timeouts, no redirects, retries, response cache or HTTP logging. */
public final class KakaoAddressGeocoder implements AddressGeocoder {
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String key;
  private final Duration timeout;

  public KakaoAddressGeocoder(HttpClient http, ObjectMapper mapper, String key, Duration timeout) {
    this.http = http;
    this.mapper = mapper;
    this.key = key;
    this.timeout = timeout;
  }

  public static String address(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 500
        || value.codePoints().anyMatch(Character::isISOControl))
      throw OwnerStoreException.invalid("address", "공백이 아닌 500자 이하의 주소를 입력해 주세요.");
    return value.strip().replaceAll("\\s+", " ");
  }

  @Override
  public Coordinates lookup(String input) {
    String address = address(input);
    if (key == null || key.isBlank()) throw unavailableConfiguration();
    try {
      var uri =
          URI.create(
              "https://dapi.kakao.com/v2/local/search/address.json?query="
                  + URLEncoder.encode(address, StandardCharsets.UTF_8)
                  + "&analyze_type=exact&page=1&size=2");
      var request =
          HttpRequest.newBuilder(uri)
              .timeout(timeout)
              .header("Authorization", "KakaoAK " + key)
              .GET()
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = response.statusCode();
      if (status == 401 || status == 403) throw unavailableConfiguration();
      if (status == 429)
        throw failure(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GEOCODE_RATE_LIMITED",
            "주소 조회 사용량을 초과했습니다. 잠시 후 다시 시도해 주세요.");
      if (status != 200) throw upstream();
      var root = mapper.readTree(response.body());
      var total = root.path("meta").path("total_count");
      var documents = root.path("documents");
      if (!total.isIntegralNumber()
          || !total.canConvertToInt()
          || total.asInt() < 0
          || !documents.isArray()) throw upstream();
      if (total.asInt() == 0 && documents.isEmpty())
        throw failure(
            HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_NOT_FOUND", "주소에 해당하는 위치를 찾지 못했습니다.");
      if (total.asInt() > 1 || documents.size() > 1)
        throw failure(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "ADDRESS_AMBIGUOUS",
            "여러 위치가 검색되었습니다. 건물번호를 포함한 주소를 선택해 주세요.");
      if (total.asInt() != 1 || documents.size() != 1) throw upstream();
      var document = documents.get(0);
      String type = document.path("address_type").asText("");
      if (!type.equals("ROAD_ADDR") && !type.equals("REGION_ADDR"))
        throw failure(
            HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_INCOMPLETE", "건물번호 또는 지번을 포함한 주소를 선택해 주세요.");
      // Kakao x is longitude; y is latitude. Keep BigDecimal precision until rounding once.
      return new Coordinates(
          new BigDecimal(document.path("y").asText("")),
          new BigDecimal(document.path("x").asText("")));
    } catch (HttpTimeoutException ex) {
      throw failure(
          HttpStatus.GATEWAY_TIMEOUT, "GEOCODE_TIMEOUT", "주소 조회 시간이 초과되었습니다. 다시 시도해 주세요.");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw failure(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODE_UNAVAILABLE", "주소 조회가 중단되었습니다.");
    } catch (OwnerStoreException ex) {
      throw ex;
    } catch (IOException | RuntimeException ex) {
      // Do not retain a cause: HTTP exceptions can contain headers, URLs and provider bodies.
      throw upstream();
    }
  }

  private static OwnerStoreException unavailableConfiguration() {
    return failure(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODE_NOT_CONFIGURED", "주소 조회 설정을 확인해 주세요.");
  }

  private static OwnerStoreException upstream() {
    return failure(HttpStatus.BAD_GATEWAY, "GEOCODE_UPSTREAM_ERROR", "주소 조회 서비스 응답을 확인할 수 없습니다.");
  }

  private static OwnerStoreException failure(HttpStatus status, String code, String message) {
    return new OwnerStoreException(status, code, message);
  }
}
