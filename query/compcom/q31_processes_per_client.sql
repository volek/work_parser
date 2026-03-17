-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q31_processes_per_client.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - var_value: текст/структура (STRING/JSON/ARRAY).
--   - process_cnt: числовой показатель (INTEGER/NUMERIC).
--   - types: категориальное значение (STRING/INTEGER).
-- ============================================================================
SELECT 
    var_caseId as client_id,
    COUNT(*) as process_cnt,
    COUNT(DISTINCT process_id) as types
FROM process_main_compact
WHERE var_caseId IS NOT NULL
GROUP BY var_caseId
ORDER BY process_cnt DESC
LIMIT 20
