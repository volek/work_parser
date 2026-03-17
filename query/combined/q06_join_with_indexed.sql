-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q06_join_with_indexed.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `process_main` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: сопоставление данных между наборами/атрибутами.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
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
--   - pv.var_category: категориальное значение (STRING/INTEGER).
--   - pv.var_path: текст/структура (STRING/JSON/ARRAY).
--   - pv.var_value: текст/структура (STRING/JSON/ARRAY).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    pm.var_caseId,
    pv.var_category,
    pv.var_path,
    pv.var_value
FROM process_main pm
JOIN process_variables_indexed pv ON pm.process_id = pv.process_id
WHERE pv.var_value IS NOT NULL
LIMIT 100
