-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q01_select_main.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `process_main` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
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
    process_id,
    var_value,
    __time as start_date,
    var_value,
    var_path,
    var_value,
    var_value,
    var_value
FROM process_main
ORDER BY __time DESC
LIMIT 100
