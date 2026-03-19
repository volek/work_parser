-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q60_json_filter_start_event.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob в Druid.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: фильтрация процессов по типу первого узла (через JSON_VALUE).
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    process_name,
    JSON_VALUE(node_instances_json, '$[0].nodeType') AS first_node_type
FROM process_main_compact
WHERE JSON_VALUE(node_instances_json, '$[0].nodeType') = 'startEvent'
LIMIT 50

