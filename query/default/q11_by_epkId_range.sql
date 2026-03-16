-- Стратегия: default
-- Клиенты в диапазоне epkId
SELECT
  process_id,
  process_name,
  var_epkId,
  __time
FROM process_main
WHERE CAST(var_epkId AS BIGINT) BETWEEN 1000000000000000000 AND 1999999999999999999
ORDER BY __time DESC
LIMIT 200;

