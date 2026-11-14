package com.togethermarket.store.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("demo-stores")
@Transactional(propagation = Propagation.MANDATORY)
public class DemoStoreRegistrationRepository {
  private final JdbcTemplate jdbc;

  public DemoStoreRegistrationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record SourceRow(
      Long id,
      String externalId,
      String name,
      String businessType,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      Long matchedStoreId) {}

  public record ExistingStore(Long id, Long ownerId, String address) {}

  public boolean lockOwner(Long ownerId) {
    // Never select email, password_hash, or other authentication data.
    return !jdbc.queryForList(
            "SELECT id FROM users WHERE id=? AND role='OWNER' FOR UPDATE", Long.class, ownerId)
        .isEmpty();
  }

  public List<SourceRow> lockSource(String externalId) {
    return jdbc.query(
        "SELECT id,external_id,name,business_type,address,latitude,longitude,matched_store_id "
            + "FROM public_store_records WHERE source='SBIZ' AND external_id=? ORDER BY id FOR UPDATE",
        (rs, n) ->
            new SourceRow(
                rs.getLong("id"),
                rs.getString("external_id"),
                rs.getString("name"),
                rs.getString("business_type"),
                rs.getString("address"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getObject("matched_store_id", Long.class)),
        externalId);
  }

  public List<ExistingStore> storesWithName(String name) {
    return jdbc.query(
        "SELECT id,owner_id,address FROM stores WHERE name=?",
        (rs, n) ->
            new ExistingStore(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("address")),
        name);
  }

  public List<ExistingStore> storeById(Long id) {
    return jdbc.query(
        "SELECT id,owner_id,address FROM stores WHERE id=?",
        (rs, n) ->
            new ExistingStore(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("address")),
        id);
  }

  public Long create(Long ownerId, SourceRow row) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO stores(owner_id,name,business_type,address,latitude,longitude,phone) "
                      + "VALUES (?,?,?,?,?,?,?)",
                  new String[] {"id"});
          statement.setLong(1, ownerId);
          statement.setString(2, row.name());
          statement.setString(3, row.businessType());
          statement.setString(4, row.address());
          statement.setBigDecimal(5, row.latitude());
          statement.setBigDecimal(6, row.longitude());
          statement.setString(7, "");
          return statement;
        },
        key);
    if (key.getKey() == null) throw new IllegalStateException("점포 ID 생성 실패");
    return key.getKey().longValue();
  }

  public int connect(Long recordId, Long storeId) {
    return jdbc.update(
        "UPDATE public_store_records SET matched_store_id=? WHERE id=? AND matched_store_id IS NULL",
        storeId,
        recordId);
  }
}
