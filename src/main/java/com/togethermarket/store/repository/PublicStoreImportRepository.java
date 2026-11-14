package com.togethermarket.store.repository;

import com.togethermarket.store.service.publicdata.PublicStoreData;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** MySQL-specific collection persistence; the caller owns the page transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PublicStoreImportRepository {
  private final JdbcTemplate jdbc;

  public PublicStoreImportRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Long> findLegacyIds(PublicStoreData row) {
    if (row.externalId() != null) {
      return jdbc.queryForList(
          "SELECT id FROM public_store_records WHERE source=? AND source_record_key IS NULL AND external_id=?",
          Long.class,
          row.source(),
          row.externalId());
    }
    return jdbc.queryForList(
        "SELECT id FROM public_store_records WHERE source=? AND source_record_key IS NULL AND external_id IS NULL AND name=? AND address=?",
        Long.class,
        row.source(),
        row.name(),
        row.address());
  }

  public int assignCollectionKey(Long recordId, String collectionKey) {
    return jdbc.update(
        "UPDATE public_store_records SET source_record_key=? WHERE id=? AND source_record_key IS NULL",
        collectionKey,
        recordId);
  }

  public void upsertRecord(PublicStoreData row, LocalDateTime collectedAt) {
    jdbc.update(
        """
        INSERT INTO public_store_records(source,source_record_key,external_id,source_row_number,name,branch_name,business_type,address,latitude,longitude,last_seen_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?) AS incoming
        ON DUPLICATE KEY UPDATE source_row_number=incoming.source_row_number,name=incoming.name,
          branch_name=incoming.branch_name,business_type=incoming.business_type,address=incoming.address,
          latitude=incoming.latitude,longitude=incoming.longitude,last_seen_at=incoming.last_seen_at
        """,
        row.source(),
        row.key(),
        row.externalId(),
        row.rowNumber(),
        row.name(),
        row.branchName(),
        row.businessType(),
        row.address(),
        row.latitude(),
        row.longitude(),
        collectedAt);
  }
}
