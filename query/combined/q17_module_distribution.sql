-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q17_module_distribution.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `combined_process_main` и (при необходимости) `combined_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в combined_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: агрегирование и расчёт метрик.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: combined_process_main.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - var_path: текст/структура (STRING/JSON/ARRAY).
--   - process_count: числовой показатель (INTEGER/NUMERIC).
--   - process_types: категориальное значение (STRING/INTEGER).
-- ============================================================================
SELECT 
    module_id,
    COUNT(*) as process_count,
    COUNT(DISTINCT process_id) as process_types
FROM combined_process_main
GROUP BY module_id
ORDER BY process_count DESC
