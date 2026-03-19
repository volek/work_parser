-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q59_json_blob_presence_flags.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля (var_blob_json) для редких доступов.
-- Типовые таблицы стратегии: обычно `process_main` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: быстрые флаги наличия ключей внутри var_blob_json.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    var_blob_json IS NOT NULL AS has_blob,
    JSON_VALUE(var_blob_json, '$.answerGFL.Status.StatusCode') IS NOT NULL AS has_answerGFL,
    JSON_VALUE(var_blob_json, '$.opHistory[0]') IS NOT NULL AS has_opHistory,
    JSON_VALUE(var_blob_json, '$.gflData') IS NOT NULL AS has_gflData,
    JSON_VALUE(var_blob_json, '$.ai_result') IS NOT NULL AS has_ai_result
FROM process_main
LIMIT 50

