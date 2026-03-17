-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q34_ucpId_from_indexed.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
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
--   - ucpId: идентификатор (STRING/UUID/INTEGER по схеме источника).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    pm.var_ucpId,
    pv.var_value as ucpId
FROM process_main_compact pm
JOIN process_variables_indexed pv ON pm.process_id = pv.process_id
WHERE pv.var_category = 'epkData'
  AND pv.var_path = 'epkEntity.ucpId'
LIMIT 50
