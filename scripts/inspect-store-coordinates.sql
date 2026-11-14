SELECT DATABASE() AS selected_database;

SELECT COUNT(*) AS total_stores,
       COALESCE(SUM(latitude IS NULL OR longitude IS NULL), 0) AS missing_pair_count,
       COALESCE(SUM(latitude IS NULL AND longitude IS NULL), 0) AS both_null_count,
       COALESCE(SUM((latitude IS NULL) <> (longitude IS NULL)), 0) AS half_null_count,
       COALESCE(SUM(latitude IS NOT NULL AND longitude IS NOT NULL
                    AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
                    AND NOT (latitude = 0 AND longitude = 0)), 0) AS usable_pair_count,
       COALESCE(SUM(latitude = 0 AND longitude = 0), 0) AS zero_pair_count
FROM stores;

-- Coordinate presence is only a rough upper bound: the Java preview also compares addresses
-- and rejects conflicting coordinate pairs across linked public records.
SELECT COUNT(*) AS missing_stores_with_linked_coordinate_pair
FROM stores s
WHERE (s.latitude IS NULL OR s.longitude IS NULL)
  AND EXISTS (
      SELECT 1 FROM public_store_records p
      WHERE p.matched_store_id = s.id
        AND p.latitude BETWEEN -90 AND 90 AND p.longitude BETWEEN -180 AND 180
        AND NOT (p.latitude = 0 AND p.longitude = 0)
  );

SELECT s.id AS store_id, s.name, s.address AS store_address,
       s.latitude AS store_latitude, s.longitude AS store_longitude,
       p.id AS public_record_id, p.source, p.address AS public_address,
       p.latitude AS public_latitude, p.longitude AS public_longitude
FROM stores s
LEFT JOIN public_store_records p ON p.matched_store_id = s.id
WHERE s.latitude IS NULL OR s.longitude IS NULL
ORDER BY s.id, p.id
LIMIT 50;

SELECT 'stores' AS table_name, COUNT(*) AS row_count FROM stores
UNION ALL SELECT 'public_store_records', COUNT(*) FROM public_store_records
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'product_purpose_tags', COUNT(*) FROM product_purpose_tags
UNION ALL SELECT 'store_hours', COUNT(*) FROM store_hours;
