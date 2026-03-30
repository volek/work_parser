-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q02_select_indexed_vars.sql`.
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
FROM combined_process_variables_indexed
ORDER BY __time DESC
LIMIT 100
