package com.togethermarket.store.service.publicdata;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PublicStoreParser {
  private final JsonMapper mapper = new JsonMapper();

  public record Page(
      int page, int pageSize, long total, List<PublicStoreData> items, int skipped) {}

  public Page parse(String source, String json, int requestedPage, int requestedSize) {
    try {
      return parseTree(source, mapper.readTree(json), requestedPage, requestedSize);
    } catch (PublicDataException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new PublicDataException("외부 API 응답 형식을 확인해 주세요. 인증 오류가 XML로 반환됐을 수 있습니다.");
    }
  }

  private Page parseTree(String source, JsonNode root, int requestedPage, int requestedSize) {
    boolean sbiz = "SBIZ".equals(source);
    if (!sbiz && !"YPAY".equals(source)) throw new PublicDataException("지원하지 않는 출처입니다.");
    if (sbiz && root.has("response")) root = root.path("response");
    JsonNode header = sbiz ? root.path("header") : root, body = sbiz ? root.path("body") : root;
    String code = text(header, "resultCode");
    if (!(sbiz ? "00".equals(code) : "0".equals(code)))
      throw new PublicDataException("외부 API가 정상 결과코드를 반환하지 않았습니다. 승인·인증키·호출 한도를 확인해 주세요.");
    int page = body.path("pageNo").asInt(-1), size = body.path("numOfRows").asInt(-1);
    long total = body.path("totalCount").asLong(-1);
    if (page != requestedPage || size != requestedSize || total < 0)
      throw new PublicDataException("외부 API 페이지 정보가 요청과 일치하지 않습니다.");
    JsonNode rows = body.path("items");
    if (rows.isObject() && rows.has("item")) rows = rows.path("item");
    List<JsonNode> raw = new ArrayList<>();
    if (rows.isArray()) rows.forEach(raw::add);
    else if (rows.isObject() && rows.has(sbiz ? "bizesId" : "aflt_nm")) raw.add(rows);
    else if (!(rows.isNull() || rows.isMissingNode() || rows.asText("").isEmpty()))
      throw new PublicDataException("점포 목록 형식을 확인해 주세요.");
    if (raw.isEmpty() && ((long) (page - 1) * size) < total)
      throw new PublicDataException("전체 건수와 점포 목록이 일치하지 않습니다.");
    List<PublicStoreData> items = new ArrayList<>();
    int skipped = 0;
    for (JsonNode row : raw) {
      try {
        if (sbiz) {
          String lat = text(row, "lat"), lon = text(row, "lon");
          String address = text(row, "rdnmAdr");
          if (address == null) address = text(row, "lnoAdr");
          items.add(
              new PublicStoreData(
                  source,
                  text(row, "bizesId"),
                  null,
                  text(row, "bizesNm"),
                  text(row, "brchNm"),
                  text(row, "indsSclsNm"),
                  address,
                  lat == null ? null : new BigDecimal(lat),
                  lon == null ? null : new BigDecimal(lon)));
        } else {
          String type = text(row, "subcls");
          if (type == null) type = text(row, "fld");
          items.add(
              new PublicStoreData(
                  source,
                  null,
                  text(row, "no"),
                  text(row, "aflt_nm"),
                  null,
                  type,
                  text(row, "addr"),
                  null,
                  null));
        }
      } catch (IllegalArgumentException ex) {
        skipped++;
      }
    }
    return new Page(page, size, total, List.copyOf(items), skipped);
  }

  private String text(JsonNode node, String key) {
    return PublicStoreData.clean(node.path(key).asText(null));
  }
}
