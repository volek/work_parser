-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q55_full_denorm.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `process_main` и (при необходимости) `process_variables_indexed`.
-- Назначение данного запроса: сопоставление данных между наборами/атрибутами.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pv.var_category: категориальное значение (STRING/INTEGER).
--   - pv.var_path: текст/структура (STRING/JSON/ARRAY).
--   - pv.var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_id,
    pm.var_value,
    pm.var_value,
    pm.var_value,
    pm.var_value,
    pv.var_category,
    pv.var_path,
    pv.var_value
FROM process_main pm
JOIN process_main pv ON pm.process_id = pv.process_id
LIMIT 100
