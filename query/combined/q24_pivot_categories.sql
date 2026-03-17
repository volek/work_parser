-- ============================================================================
-- Описание запроса:
-- Файл: `combined/q24_pivot_categories.sql`.
-- Стратегия: Combined (Tiered Hot/Warm/Cold).
-- Модель стратегии: горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов.
-- Типовые таблицы стратегии: обычно `process_main` и (при необходимости) `process_variables_indexed`.
-- При parser.warmVariablesLimit (10..1010) число записей в process_variables_indexed на процесс может быть ограничено.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_main.
-- 2) Объединение наборов через JOIN для связывания контекста процесса и/или переменных.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - pm.process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - epk_cnt: числовой показатель (INTEGER/NUMERIC).
--   - static_cnt: числовой показатель (INTEGER/NUMERIC).
-- ============================================================================
SELECT 
    pm.process_id,
    pm.process_name,
    SUM(CASE WHEN pv.var_category = 'epkData' THEN 1 ELSE 0 END) as epk_cnt,
    SUM(CASE WHEN pv.var_category = 'staticData' THEN 1 ELSE 0 END) as static_cnt
FROM process_main pm
JOIN process_variables_indexed pv ON pm.process_id = pv.process_id
WHERE pv.var_path = 'ucpId'
GROUP BY pm.process_id, pm.process_name
LIMIT 50
