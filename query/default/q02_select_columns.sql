-- ============================================================================
-- Стратегия: Default (используем общую таблицу процессов process_main).
-- Выборка основных колонок и пример колонок переменных (для process_main без точек в имени).
-- ============================================================================
SELECT
    id,
    process_id,
    process_name,
    state,
    __time,
    module_id,
    business_key
    -- при наличии в данных можно добавить колонки с точкой (в кавычках):
    -- , "variables.caseId"
    -- , "variables.staticData.caseId"
FROM process_main
ORDER BY __time DESC
LIMIT 50
