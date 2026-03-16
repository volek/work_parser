-- Стратегия: default
-- Процессы с заполненным var_ucpId
SELECT
  process_id,
  process_name,
  var_ucpId,
  __time
FROM process_main
WHERE var_ucpId IS NOT NULL
ORDER BY __time DESC
LIMIT 200;

