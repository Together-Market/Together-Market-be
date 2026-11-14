
WITH scoped AS (
    SELECT p.*,
        CASE
            WHEN REGEXP_LIKE(
                REPLACE(p.address, ' ', ''),
                '^경기(도)?용인시기흥구죽전로15번길[0-9]'
            ) THEN 'BOJEONG'
            ELSE 'DONGBAEK'
        END AS market_code
    FROM together_market.public_store_records p
    WHERE p.source IN ('SBIZ', 'YPAY')
      AND (
          REGEXP_LIKE(
              REPLACE(p.address, ' ', ''),
              '^경기(도)?용인시기흥구죽전로15번길[0-9]'
          )
          OR REGEXP_LIKE(
              REPLACE(p.address, ' ', ''),
              '^경기(도)?용인시기흥구(동백죽전대로444|동백5로22|동백중앙로225-6)([^0-9-]|$)'
          )
      )
)
SELECT
    p.market_code,
    p.source,
    p.id AS public_record_id,
    p.external_id,
    p.source_record_key,
    p.source_row_number,
    p.name,
    p.branch_name,
    p.business_type,
    p.address,
    p.latitude,
    p.longitude,
    p.matched_store_id,
    s.id AS store_id,
    s.owner_id,
    s.name AS store_name,
    s.address AS store_address
FROM scoped p
LEFT JOIN together_market.stores s ON s.id = p.matched_store_id
ORDER BY p.market_code, p.source, p.address, p.name, p.id;