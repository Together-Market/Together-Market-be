package com.togethermarket.demo;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CsvDemoBundleTest {
  @Test
  void packagedSourcesAndCatalogPassChecksumsAndMatchApprovedCounts() throws Exception {
    var bundle = CsvDemoBundle.load();
    assertThat(bundle.catalog().stores()).hasSize(2224);
    assertThat(bundle.rows()).hasSize(3265);
    assertThat(bundle.catalog().stores().stream().flatMap(s -> s.products().stream()))
        .hasSize(1980);
    assertThat(bundle.manifest().statuses()).containsEntry("ON_SALE", 1907);
  }

  @Test
  void corruptContentIsRejectedBeforeDatabaseAccess() {
    assertThatThrownBy(() -> CsvDemoBundle.verifyChecksum(new byte[] {1}, "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void csvPreservesQuotedCommasEscapedQuotesAndMultilineFields() {
    var rows = CsvRows.parse("\ufeffname,address\r\n\"가게, 본점\",\"주소 \"\"가\"\"\n둘째 줄\"\r\n");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("name", "가게, 본점")
        .containsEntry("address", "주소 \"가\"\n둘째 줄");
    assertThatThrownBy(() -> CsvRows.parse("a,b\n\"unclosed,b"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
