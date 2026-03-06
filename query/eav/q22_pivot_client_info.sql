-- ============================================================================
-- Описание запроса:
-- Файл: `eav/q22_pivot_client_info.sql`.
-- Стратегия: EAV (Entity-Attribute-Value).
-- Модель стратегии: данные процесса разделены на сущность процесса и набор переменных по путям/атрибутам.
-- Типовые таблицы стратегии: обычно `process_events` + `process_variables` (в текущем наборе также встречаются унифицированные представления).
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pe.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - epkId_cnt: числовой показатель (INTEGER/NUMERIC).
--   - fio_cnt: числовой показатель (INTEGER/NUMERIC).
--   - caseId_cnt: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    pe.process_id,
    pe.process_id as process_name,
    SUM(CASE WHEN pv.var_path = 'epkId' THEN 1 ELSE 0 END) as epkId_cnt,
    SUM(CASE WHEN pv.var_path = 'fio' THEN 1 ELSE 0 END) as fio_cnt,
    SUM(CASE WHEN pv.var_path = 'caseId' THEN 1 ELSE 0 END) as caseId_cnt
FROM process_events pe
JOIN process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path IN ('epkId', 'fio', 'caseId')
GROUP BY pe.process_id
