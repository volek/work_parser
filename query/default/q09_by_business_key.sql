-- Стратегия: default
-- Процессы с заполненным business_key
SELECT
  process_id,
  process_name,
  business_key,
  state,
  __time
FROM default_process_default
WHERE business_key IS NOT NULL
ORDER BY __time DESC
LIMIT 200;

