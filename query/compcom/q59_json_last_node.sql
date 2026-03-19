-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q59_json_last_node.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob в Druid.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: извлечение последнего узла процесса из JSON (node_instances_json).
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 6) Упорядочивание результата через ORDER BY.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    process_name,
    JSON_VALUE(node_instances_json, '$[-1].nodeName') AS last_node_name,
    JSON_VALUE(node_instances_json, '$[-1].state') AS last_node_state
FROM process_main_compact
WHERE node_instances_json IS NOT NULL
ORDER BY __time DESC
LIMIT 50

