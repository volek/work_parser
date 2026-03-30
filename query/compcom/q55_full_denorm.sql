-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q55_full_denorm.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: сопоставление данных между наборами/атрибутами.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pv.var_category: категориальное значение (STRING/INTEGER).
--   - pv.var_path: текст/структура (STRING/JSON/ARRAY).
--   - pv.var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    pm.var_caseId,
    pm.var_epkId,
    pm.var_fio,
    pm.var_ucpId,
    pv.var_category,
    pv.var_path,
    pv.var_value
FROM compcom_process_main_compact pm
JOIN compcom_process_variables_indexed pv ON pm.process_id = pv.process_id
LIMIT 100
