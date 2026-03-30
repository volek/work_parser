-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q57_json_blob_gfl_status_dist.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля (var_blob_json) для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- Назначение данного запроса: агрегирование по статусу, извлечённому из JSON cold blob.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 4) Агрегация данных (GROUP BY и агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на группу.
-- ============================================================================
SELECT
    JSON_VALUE(var_blob_json, '$.answerGFL.Status.StatusCode') AS gfl_status_code,
    COUNT(*) AS cnt
FROM combined_process_main
WHERE var_blob_json IS NOT NULL
GROUP BY 1
ORDER BY cnt DESC
LIMIT 50

