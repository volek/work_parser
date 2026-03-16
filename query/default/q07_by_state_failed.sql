-- Стратегия: default
-- Завершившиеся с ошибкой (state = 2)
SELECT
  process_id,
  process_name,
  state,
  error,
  __time
FROM process_main
WHERE state = 2
ORDER BY __time DESC
LIMIT 200;

