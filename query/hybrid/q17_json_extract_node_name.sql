-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q17_json_extract_node_name.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `hybrid_process_hybrid`.
-- Назначение данного запроса: извлечение/анализ JSON-полей.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: hybrid_process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - first_node_name: текст/структура (STRING/JSON/ARRAY).
--   - first_node_type: категориальное значение (STRING/INTEGER).
-- ============================================================================
SELECT 
    process_id,
    process_name,
    JSON_VALUE(node_instances_json, '$[0].nodeName') as first_node_name,
    JSON_VALUE(node_instances_json, '$[0].nodeType') as first_node_type
FROM hybrid_process_hybrid
WHERE JSON_VALUE(node_instances_json, '$[0].nodeType') = 'startEvent'
LIMIT 50
