-- Стратегия: default
-- Запуски в состоянии "running" (state = 1)
SELECT
  process_id,
  process_name,
  state,
  __time
FROM process_main
WHERE state = 1
ORDER BY __time DESC
LIMIT 200;

