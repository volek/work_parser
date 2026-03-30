-- Стратегия: default
-- Фильтр по шаблону var_caseId
SELECT
  process_id,
  process_name,
  var_caseId,
  __time
FROM default_process_default
WHERE var_caseId LIKE '____-____-%'
ORDER BY __time DESC
LIMIT 200;

