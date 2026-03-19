-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q57_json_node_last_state.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: hot/warm поля в колонках + JSON-блобы для полноты данных.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: извлечение состояния последнего узла из node_instances_json.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 6) Упорядочивание результата через ORDER BY.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    process_name,
    JSON_VALUE(node_instances_json, '$[-1].nodeName') AS last_node_name,
    JSON_VALUE(node_instances_json, '$[-1].state') AS last_node_state
FROM process_main
WHERE node_instances_json IS NOT NULL
ORDER BY __time DESC
LIMIT 50

