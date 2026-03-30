-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q01_select_main.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в combined_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 6) Упорядочивание результата через ORDER BY.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - start_date: дата/время (TIMESTAMP/DATE).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - var_path: текст/структура (STRING/JSON/ARRAY).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    process_id,
    process_name,
    state,
    __time as start_date,
    var_caseId,
    var_epkId,
    var_fio,
    var_ucpId,
    module_id
FROM combined_process_main
ORDER BY __time DESC
LIMIT 100
