-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q52_status_code_dist.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: агрегирование и расчёт метрик.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - var_staticData_statusCode: категориальное значение (STRING/INTEGER).
--   - cnt_total: числовой показатель (INTEGER/NUMERIC).
--   - first_occurrence: дата/время (TIMESTAMP/DATE).
--   - last_occurrence: дата/время (TIMESTAMP/DATE).
-- ============================================================================
SELECT 
    var_staticData_statusCode,
    COUNT(*) as cnt_total,
    MIN(__time) as first_occurrence,
    MAX(__time) as last_occurrence
FROM process_main
WHERE var_staticData_statusCode IS NOT NULL
GROUP BY var_staticData_statusCode
ORDER BY cnt_total DESC
