-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q12_failed_processes.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    pvi.process_id,
    pm.process_name,
    pm.state,
    pvi.var_value as statusCode
FROM process_variables_indexed pvi
JOIN process_main_compact pm ON pvi.process_id = pm.process_id
WHERE pvi.var_path = 'statusCode' AND pvi.var_value = '1'
