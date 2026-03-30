-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q39_process_with_all_data.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `compcom_process_main_compact` и (при необходимости) `compcom_process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в compcom_process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: compcom_process_main_compact.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - pm.var_value: текст/структура (STRING/JSON/ARRAY).
--   - categories: тип определяется выражением в SELECT.
--   - var_count: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.var_caseId,
    pm.var_epkId,
    pm.var_fio,
    pm.var_ucpId,
    COUNT(DISTINCT pv.var_category) as categories,
    COUNT(pv.var_path) as var_count
FROM compcom_process_main_compact pm
LEFT JOIN compcom_process_variables_indexed pv ON pm.process_id = pv.process_id
GROUP BY pm.process_id, pm.var_caseId, pm.var_epkId, pm.var_fio, pm.var_ucpId
LIMIT 50
