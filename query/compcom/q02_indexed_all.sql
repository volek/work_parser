-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q02_indexed_all.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_category: категориальное значение (STRING/INTEGER).
--   - var_path: текст/структура (STRING/JSON/ARRAY).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - var_type: категориальное значение (STRING/INTEGER).
-- ============================================================================
SELECT 
    process_id,
    var_category,
    var_path,
    var_value,
    var_type
FROM compcom_process_variables_indexed
LIMIT 100
