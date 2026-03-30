-- Стратегия: default
-- Последние 24 часа, все процессы
SELECT *
FROM default_process_default
WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '1' DAY
ORDER BY __time DESC;

