-- ============================================================================
-- Стратегия: Default (используем общую таблицу процессов default_process_default).
-- Выборка основных колонок и пример колонок переменных (для default_process_default без точек в имени).
-- ============================================================================
SELECT
    process_id,
    process_name,
    state,
    __time,
    module_id,
    business_key
    -- при наличии в данных можно добавить колонки с точкой (в кавычках):
    -- , "variables.caseId"
    -- , "variables.staticData.caseId"
FROM default_process_default
ORDER BY __time DESC
LIMIT 50
