-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q33_combined_filter.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в combined_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: фильтрация записей по условиям.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 6) Упорядочивание результата через ORDER BY.
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
--   - var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    process_id,
    process_name,
    state,
    var_caseId,
    var_epkId
FROM combined_process_main
WHERE state IS NOT NULL
  AND state IN (1, 2)
  AND __time >= CURRENT_TIMESTAMP - INTERVAL '30' DAY
ORDER BY __time DESC
