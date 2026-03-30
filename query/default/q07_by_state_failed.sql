-- Стратегия: default
-- Завершившиеся с ошибкой (state = 2)
SELECT
  process_id,
  process_name,
  state,
  error,
  __time
FROM default_process_default
WHERE state = 2
ORDER BY __time DESC
LIMIT 200;

