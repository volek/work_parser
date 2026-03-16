-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q20_filter_running_processes.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: фильтрация записей по условиям.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 6) Упорядочивание результата через ORDER BY.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - var_caseId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_epkId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_fio: текст/структура (STRING/JSON/ARRAY).
--   - start_date: дата/время (TIMESTAMP/DATE).
--   - running_minutes: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    process_id,
    process_name,
    var_caseId,
    var_epkId,
    var_fio,
    __time as start_date,
    TIMESTAMPDIFF(MINUTE, __time, CURRENT_TIMESTAMP) as running_minutes
FROM process_main
WHERE state = 1
ORDER BY __time
