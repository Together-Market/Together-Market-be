-- 확인된 쥬네브 3개 건물의 공공데이터를 양 출처에서 다시 조회한다.
-- 주변 상권 전체 범위가 아니며, 매칭 확정이나 DB 변경을 수행하지 않는다.
-- 숫자 뒤 경계를 검사해 4440, 22-1, 225-60 등의 다른 건물을 포함하지 않는다.
SELECT
    'DONGBAEK' AS market_code,
    source,
    id AS public_record_id,
    external_id,
    source_record_key,
    source_row_number,
    name,
    branch_name,
    business_type,
    address,
    latitude,
    longitude,
    matched_store_id
FROM together_market.public_store_records
WHERE source IN ('SBIZ', 'YPAY')
  AND REGEXP_LIKE(
    REPLACE(address, ' ', ''),
    '^경기(도)?용인시기흥구(동백죽전대로444|동백5로22|동백중앙로225-6)([^0-9-]|$)'
  )
ORDER BY source, address, name, external_id;