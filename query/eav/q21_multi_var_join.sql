-- ============================================================================
-- Описание запроса:
-- Файл: `eav/q21_multi_var_join.sql`.
-- Стратегия: EAV (Entity-Attribute-Value).
-- Модель стратегии: данные процесса разделены на сущность процесса и набор переменных по путям/атрибутам.
-- Типовые таблицы стратегии: обычно `process_events` + `process_variables` (в текущем наборе также встречаются унифицированные представления).
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
--   - pe.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pe.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - epkId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - caseId: идентификатор (STRING/UUID/INTEGER по схеме источника).
-- ============================================================================
SELECT 
    pe.process_id,
    pe.process_id,
    epk.var_value as epkId,
    cid.var_value as caseId
FROM process_main pe
LEFT JOIN process_main epk 
    ON pe.process_id = epk.process_id AND epk.var_path = 'epkId'
LEFT JOIN process_main cid 
    ON pe.process_id = cid.process_id AND cid.var_path = 'caseId'
WHERE epk.var_value IS NOT NULL OR cid.var_value IS NOT NULL
LIMIT 50
