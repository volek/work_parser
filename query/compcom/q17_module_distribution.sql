-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q17_module_distribution.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: агрегирование и расчёт метрик.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - var_path: текст/структура (STRING/JSON/ARRAY).
--   - process_count: числовой показатель (INTEGER/NUMERIC).
--   - process_types: категориальное значение (STRING/INTEGER).
-- ============================================================================
SELECT 
    module_id,
    COUNT(*) as process_count,
    COUNT(DISTINCT process_id) as process_types
FROM compcom_process_main_compact
GROUP BY module_id
ORDER BY process_count DESC
