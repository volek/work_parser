-- Стратегия: default
-- Запуски в состоянии "running" (state = 1)
SELECT
  process_id,
  process_name,
  state,
  __time
FROM default_process_default
WHERE state = 1
ORDER BY __time DESC
LIMIT 200;

