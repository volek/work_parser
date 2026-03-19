-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q53_json_staticData_extract.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: hot/warm поля в колонках + JSON-блобы для полноты данных.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: извлечение полей из полного JSON-блоба staticData (var_staticData_json).
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- ============================================================================
SELECT
    process_id,
    process_name,
    JSON_VALUE(var_staticData_json, '$.casePublicId') AS case_public_id,
    JSON_VALUE(var_staticData_json, '$.statusCode') AS status_code,
    JSON_VALUE(var_staticData_json, '$.registrationTime') AS registration_time
FROM process_main
WHERE var_staticData_json IS NOT NULL
LIMIT 50

