-- Стратегия: default
-- Фильтр по модулю
SELECT
  process_id,
  process_name,
  module_id,
  state,
  __time
FROM default_process_default
WHERE module_id = 'psi-fl-5g'
ORDER BY __time DESC
LIMIT 200;

