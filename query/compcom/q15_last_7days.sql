-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q15_last_7days.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: фильтрация записей по условиям.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - day_ts: тип определяется выражением в SELECT.
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - cnt_total: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    DATE_TRUNC('day', __time) as day_ts,
    process_id,
    COUNT(*) as cnt_total
FROM compcom_process_main_compact
WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '7' DAY
GROUP BY 1, 2
ORDER BY day_ts DESC, cnt_total DESC
