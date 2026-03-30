-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q30_hot_col_nulls.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в combined_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - null_epk: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - null_fio: текст/структура (STRING/JSON/ARRAY).
--   - null_case: тип определяется выражением в SELECT.
--   - total: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    SUM(CASE WHEN var_epkId IS NULL THEN 1 ELSE 0 END) as null_epk,
    SUM(CASE WHEN var_fio IS NULL THEN 1 ELSE 0 END) as null_fio,
    SUM(CASE WHEN var_caseId IS NULL THEN 1 ELSE 0 END) as null_case,
    COUNT(*) as total
FROM combined_process_main
