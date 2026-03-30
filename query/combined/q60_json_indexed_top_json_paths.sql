-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q60_json_indexed_top_json_paths.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные; часть значений может храниться как JSON в combined_process_variables_indexed.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- Назначение данного запроса: найти, какие JSON-пути чаще всего встречаются в индексной таблице.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_variables_indexed.
-- 3) Применение фильтров WHERE для отбора JSON-переменных.
-- 4) Агрегация данных (GROUP BY и агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
-- ============================================================================
SELECT
    var_category,
    var_path,
    COUNT(*) AS cnt
FROM combined_process_variables_indexed
WHERE var_type = 'json'
GROUP BY 1, 2
ORDER BY cnt DESC
LIMIT 50

