-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q56_json_answerGFL_status_dist.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: hot/warm поля в колонках + JSON-блобы для полноты данных.
-- Типовые таблицы стратегии: обычно `hybrid_process_hybrid`.
-- Назначение данного запроса: распределение процессов по StatusCode из var_answerGFL_json.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: hybrid_process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
-- ============================================================================
SELECT
    JSON_VALUE(var_answerGFL_json, '$.Status.StatusCode') AS gfl_status_code,
    COUNT(*) AS cnt
FROM hybrid_process_hybrid
WHERE var_answerGFL_json IS NOT NULL
GROUP BY 1
ORDER BY cnt DESC
LIMIT 50

