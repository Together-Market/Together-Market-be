package com.togethermarket.store.service.publicdata;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PublicStoreClient {
  private final HttpClient http;
  private final PublicStoreParser parser;

  @org.springframework.beans.factory.annotation.Autowired
  public PublicStoreClient(PublicStoreParser parser) {
    this(parser, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  PublicStoreClient(PublicStoreParser parser, HttpClient http) {
    this.parser = parser;
    this.http = http;
  }

  public PublicStoreParser.Page fetch(
      String source, String key, int page, int size, String district) {
    if (key == null || key.isBlank()) throw new PublicDataException("해당 출처의 일반 인증키 환경변수가 필요합니다.");
    if (page < 1 || size < 1 || size > 1000) throw new PublicDataException("페이지와 페이지 크기를 확인해 주세요.");
    Map<String, String> params = new LinkedHashMap<>();
    params.put("serviceKey", normalizeKey(key));
    params.put("pageNo", Integer.toString(page));
    params.put("numOfRows", Integer.toString(size));
    String endpoint;
    if ("YPAY".equals(source)) endpoint = "https://apis.data.go.kr/4050000/ypay/getYpay";
    else if ("SBIZ".equals(source)) {
      if (!Set.of("41461", "41463", "41465").contains(district))
        throw new PublicDataException("용인시 구 코드 41461/41463/41465를 사용해 주세요.");
      endpoint = "https://apis.data.go.kr/B553077/api/open/sdsc2/storeListInDong";
      params.put("divId", "signguCd");
      params.put("key", district);
      params.put("type", "json");
    } else throw new PublicDataException("지원하지 않는 출처입니다.");
    String query =
        params.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    try {
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
              .timeout(Duration.ofSeconds(30))
              .GET()
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200)
        throw new PublicDataException("외부 API HTTP 오류: " + response.statusCode());
      return parser.parse(source, response.body(), page, size);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new PublicDataException("공공데이터 수집이 중단됐습니다.");
    } catch (PublicDataException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new PublicDataException("외부 API 연결 또는 응답에 실패했습니다. 인증키가 포함된 URL은 로그에 남기지 않습니다.");
    }
  }

  private String normalizeKey(String key) {
    String value = key.strip();
    // Accept a portal-provided encoded key while preserving literal plus signs.
    if (java.util.regex.Pattern.compile("%[0-9a-fA-F]{2}").matcher(value).find()) {
      try {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException ex) {
        throw new PublicDataException("인증키 형식을 확인해 주세요.");
      }
    }
    return value;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
