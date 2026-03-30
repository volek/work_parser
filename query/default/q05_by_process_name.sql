-- Стратегия: default
-- Фильтр по имени процесса
SELECT *
FROM default_process_default
WHERE process_name = 'uvskRemainderReturnCR-Service'
ORDER BY __time DESC
LIMIT 200;

