-- Стратегия: default
-- Последние 7 дней, базовые поля
SELECT
  process_id,
  process_name,
  state,
  __time,
  module_id
FROM process_main
WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '7' DAY
ORDER BY __time DESC;

