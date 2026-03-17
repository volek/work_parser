-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q57_warm_limit_impact.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение: число warm-переменных на процесс (при лимите — не более N на процесс).
--
-- Логика: агрегация по process_id из process_variables_indexed.
-- ============================================================================
SELECT 
    process_id,
    COUNT(*) as warm_var_count
FROM process_variables_indexed
GROUP BY process_id
ORDER BY warm_var_count DESC
LIMIT 100
