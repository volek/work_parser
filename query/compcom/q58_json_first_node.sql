-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q58_json_first_node.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob в Druid.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: извлечение первого узла процесса из JSON (node_instances_json).
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    process_name,
    JSON_VALUE(node_instances_json, '$[0].nodeName') AS first_node_name,
    JSON_VALUE(node_instances_json, '$[0].nodeType') AS first_node_type
FROM process_main_compact
WHERE node_instances_json IS NOT NULL
LIMIT 50

