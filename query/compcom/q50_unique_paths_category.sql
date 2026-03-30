-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q50_unique_paths_category.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
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
--   - var_category: категориальное значение (STRING/INTEGER).
--   - unique_paths: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    var_category,
    COUNT(DISTINCT var_path) as unique_paths
FROM compcom_process_variables_indexed
GROUP BY var_category
ORDER BY unique_paths DESC
