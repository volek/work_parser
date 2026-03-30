-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q42_case_public_id.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - case_public_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    pv.var_value as case_public_id
FROM compcom_process_main_compact pm
JOIN compcom_process_variables_indexed pv ON pm.process_id = pv.process_id
    AND pv.var_category = 'staticData' AND pv.var_path = 'casePublicId' AND pv.var_value IS NOT NULL
LIMIT 50
