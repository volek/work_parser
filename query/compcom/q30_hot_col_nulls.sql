-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q30_hot_col_nulls.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
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
FROM process_main_compact
