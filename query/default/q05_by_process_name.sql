-- Стратегия: default
-- Фильтр по имени процесса
SELECT *
FROM process_main
WHERE process_name = 'uvskRemainderReturnCR-Service'
ORDER BY __time DESC
LIMIT 200;

