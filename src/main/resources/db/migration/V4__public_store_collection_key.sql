-- V3 belongs to BE3 (#11). Merge/apply it before this migration.
-- Fail BEFORE this non-transactional MySQL DDL if its prerequisite is missing.
SELECT recommendation_claim_token, recommendation_deadline_at FROM purchase_requests WHERE 1=0;
-- Nullable for pre-existing records; legacy duplicates must be reconciled explicitly.
ALTER TABLE public_store_records
  ADD COLUMN source_record_key CHAR(64) NULL COMMENT '출처 내 수집 중복 판별 키',
  ADD UNIQUE KEY uk_public_store_source_key (source, source_record_key);
