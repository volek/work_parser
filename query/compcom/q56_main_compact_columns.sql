-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q56_main_compact_columns.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- Назначение: выборка только из process_main_compact (колонка var_blob_json отсутствует).
--
-- Логика: выбор из process_main_compact, фильтр по state, лимит.
-- ============================================================================
SELECT 
    process_id,
    process_name,
    state,
    module_id,
    var_caseId,
    var_epkId,
    var_fio,
    __time
FROM process_main_compact
WHERE state = 1
ORDER BY __time DESC
LIMIT 50
