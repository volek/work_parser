-- ============================================================================
-- Описание запроса:
-- Файл: `compcom/q23_multi_category_join.sql`.
-- Стратегия: Compcom (Compact combined, no cold blob).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные, без cold blob.
-- Типовые таблицы стратегии: обычно `process_main_compact` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: сопоставление данных между наборами/атрибутами.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main_compact.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - epk_data: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - static_data: тип определяется выражением в SELECT.
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    epk.var_value as epk_data,
    st.var_value as static_data
FROM process_main_compact pm
LEFT JOIN process_variables_indexed epk 
    ON pm.process_id = epk.process_id 
    AND epk.var_category = 'epkData' 
    AND epk.var_path = 'epkEntity.ucpId'
LEFT JOIN process_variables_indexed st 
    ON pm.process_id = st.process_id 
    AND st.var_category = 'staticData' 
    AND st.var_path = 'clientEpkId'
LIMIT 50
