-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q58_json_blob_ai_result_fields.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля (var_blob_json) для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- Назначение данного запроса: выборка потенциальных полей из ai_result внутри JSON cold blob.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Примечание:
-- - Структура `ai_result` может отличаться между процессами; JSON_VALUE вернёт NULL, если пути нет.
-- ============================================================================
SELECT
    process_id,
    JSON_VALUE(var_blob_json, '$.ai_result.decision') AS ai_decision,
    JSON_VALUE(var_blob_json, '$.ai_result.score') AS ai_score,
    JSON_VALUE(var_blob_json, '$.ai_result.model') AS ai_model
FROM combined_process_main
WHERE var_blob_json IS NOT NULL
LIMIT 50

