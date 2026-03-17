-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q52_hot_columns_filled.sql`.
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
--   - total: числовой показатель (INTEGER/NUMERIC).
--   - with_epk: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - with_fio: текст/структура (STRING/JSON/ARRAY).
--   - with_case: тип определяется выражением в SELECT.
-- ============================================================================
SELECT 
    COUNT(*) as total,
    SUM(CASE WHEN var_epkId IS NOT NULL THEN 1 ELSE 0 END) as with_epk,
    SUM(CASE WHEN var_fio IS NOT NULL THEN 1 ELSE 0 END) as with_fio,
    SUM(CASE WHEN var_caseId IS NOT NULL THEN 1 ELSE 0 END) as with_case
FROM process_main_compact
