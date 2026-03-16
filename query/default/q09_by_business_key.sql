-- Стратегия: default
-- Процессы с заполненным business_key
SELECT
  process_id,
  process_name,
  business_key,
  state,
  __time
FROM process_main
WHERE business_key IS NOT NULL
ORDER BY __time DESC
LIMIT 200;

