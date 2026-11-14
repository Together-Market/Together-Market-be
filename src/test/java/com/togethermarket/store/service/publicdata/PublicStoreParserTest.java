package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PublicStoreParserTest {
  static final String YPAY =
      """
    {"resultCode":0,"numOfRows":3,"pageNo":5,"totalCount":34453,
    "items":[{"no":12,"fld":"학원","subcls":"기타 교육기관","aflt_nm":"테스트 학원","addr":"경기 용인시 예시로 1"}]}
    """;
  private final PublicStoreParser parser = new PublicStoreParser();

  @Test
  void ypayIdentityIsNotRowNumber() {
    var page = parser.parse("YPAY", YPAY, 5, 3);
    assertEquals(34453, page.total());
    var row = page.items().getFirst();
    assertNull(row.externalId());
    assertEquals("12", row.rowNumber());
    assertNull(row.latitude());
    assertEquals(
        row.key(),
        parser
            .parse("YPAY", YPAY.replace("\"no\":12", "\"no\":999"), 5, 3)
            .items()
            .getFirst()
            .key());
    assertEquals("경기도 용인시", PublicStoreData.normalizedAddress(" 경기   용인시 "));
  }

  @Test
  void sbizNestedResponseAndRounding() {
    var page =
        parser.parse(
            "SBIZ",
            """
      {"header":{"resultCode":"00"},"body":{"numOfRows":1,"pageNo":1,"totalCount":1,
      "items":[{"bizesId":"MA1","bizesNm":"카페","brchNm":"","rdnmAdr":"","lnoAdr":"경기도 용인시 예시동 1","lon":127.108150977326,"lat":37.2664941811046}]}}
      """,
            1,
            1);
    var row = page.items().getFirst();
    assertEquals("MA1", row.externalId());
    assertNull(row.branchName());
    assertEquals(new BigDecimal("37.2664942"), row.latitude());
    assertEquals(new BigDecimal("127.1081510"), row.longitude());
  }

  @Test
  void providerErrorsAndPageMismatchFailWithoutEchoingPayload() {
    for (String body :
        new String[] {
          "<error>SECRET</error>",
          "{\"resultCode\":30,\"secret\":\"SECRET\"}",
          "{}",
          YPAY.replace("\"pageNo\":5", "\"pageNo\":4")
        }) {
      var ex = assertThrows(PublicDataException.class, () -> parser.parse("YPAY", body, 5, 3));
      assertFalse(ex.getMessage().contains("SECRET"));
    }
  }

  @Test
  void invalidRowsAreCountedAsSkipped() {
    assertEquals(1, parser.parse("YPAY", YPAY.replace("테스트 학원", " "), 5, 3).skipped());
  }
}
