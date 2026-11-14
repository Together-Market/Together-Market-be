package com.togethermarket.store.repository;

import com.togethermarket.store.service.geocoding.Coordinates;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinateBackfillRepository {
  public record MissingStore(long id, String address, BigDecimal latitude, BigDecimal longitude) {}

  public record PublicCoordinate(
      long id, String address, BigDecimal latitude, BigDecimal longitude) {}

  private final JdbcTemplate jdbc;

  public CoordinateBackfillRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<MissingStore> missing(long afterId, long throughId, int limit) {
    return jdbc.query(
        """
        SELECT id, address, latitude, longitude FROM stores
        WHERE id > ? AND id <= ? AND (latitude IS NULL OR longitude IS NULL)
        ORDER BY id LIMIT ?
        """,
        (rs, n) ->
            new MissingStore(
                rs.getLong("id"),
                rs.getString("address"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude")),
        afterId,
        throughId,
        limit);
  }

  public List<PublicCoordinate> linked(long storeId) {
    return jdbc.query(
        """
        SELECT id, address, latitude, longitude FROM public_store_records
        WHERE matched_store_id = ? ORDER BY id
        """,
        (rs, n) ->
            new PublicCoordinate(
                rs.getLong("id"),
                rs.getString("address"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude")),
        storeId);
  }

  /** One atomic statement per store; concurrent owner edits or source changes cause a skip. */
  public boolean fill(MissingStore store, PublicCoordinate source, Coordinates pair) {
    return jdbc.update(
            """
        UPDATE stores s SET latitude = ?, longitude = ?
        WHERE s.id = ? AND (s.latitude IS NULL OR s.longitude IS NULL)
          AND CAST(s.address AS BINARY) = CAST(? AS BINARY)
          AND s.latitude <=> ? AND s.longitude <=> ?
          AND EXISTS (SELECT 1 FROM public_store_records p
            WHERE p.id = ? AND p.matched_store_id = s.id
              AND CAST(p.address AS BINARY) = CAST(? AS BINARY)
              AND p.latitude <=> ? AND p.longitude <=> ?)
        """,
            pair.latitude(),
            pair.longitude(),
            store.id(),
            store.address(),
            store.latitude(),
            store.longitude(),
            source.id(),
            source.address(),
            source.latitude(),
            source.longitude())
        == 1;
  }
}
