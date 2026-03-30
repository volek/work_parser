-- Стратегия: default
-- Последние 7 дней, базовые поля
SELECT
  process_id,
  process_name,
  state,
  __time,
  module_id
FROM default_process_default
WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '7' DAY
ORDER BY __time DESC;

