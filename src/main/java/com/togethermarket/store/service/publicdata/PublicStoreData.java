package com.togethermarket.store.service.publicdata;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

public record PublicStoreData(
    String source,
    String externalId,
    String rowNumber,
    String name,
    String branchName,
    String businessType,
    String address,
    BigDecimal latitude,
    BigDecimal longitude) {
  public static String clean(String s) {
    if (s == null || s.isBlank()) return null;
    return s.strip();
  }

  public static String normalize(String s) {
    return s == null
        ? ""
        : Normalizer.normalize(s, Normalizer.Form.NFKC)
            .strip()
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
  }

  public static String normalizedAddress(String s) {
    return normalize(s).replaceFirst("^경기 ", "경기도 ");
  }

  public String key() {
    String identity =
        externalId != null
            ? "id\0" + externalId
            : "name-address\0" + normalize(name) + "\0" + normalizedAddress(address);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public PublicStoreData {
    source = clean(source);
    externalId = clean(externalId);
    rowNumber = clean(rowNumber);
    name = clean(name);
    branchName = clean(branchName);
    businessType = clean(businessType);
    address = clean(address);
    if (!"SBIZ".equals(source) && !"YPAY".equals(source))
      throw new IllegalArgumentException("Unknown source");
    if (name == null
        || address == null
        || name.length() > 200
        || address.length() > 700
        || (externalId != null && externalId.length() > 200)
        || (branchName != null && branchName.length() > 200)
        || (businessType != null && businessType.length() > 150)
        || (rowNumber != null && rowNumber.length() > 50))
      throw new IllegalArgumentException("Invalid record fields");
    if ("SBIZ".equals(source) && externalId == null)
      throw new IllegalArgumentException("Missing provider ID");
    if ((latitude == null) != (longitude == null))
      throw new IllegalArgumentException("Incomplete coordinates");
    if (latitude != null) {
      if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
          || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0)
        throw new IllegalArgumentException("Invalid coordinates");
      latitude = latitude.setScale(7, RoundingMode.HALF_UP);
      longitude = longitude.setScale(7, RoundingMode.HALF_UP);
    }
  }
}
