-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q46_null_analysis.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `hybrid_process_hybrid`.
-- Назначение данного запроса: получение детальной выборки для анализа.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: hybrid_process_hybrid.
-- 4) Агрегация данных (GROUP BY и/или агрегатные функции).
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: агрегированный набор (метрики/группы).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - total: числовой показатель (INTEGER/NUMERIC).
--   - null_caseId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - null_epkId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - null_fio: текст/структура (STRING/JSON/ARRAY).
--   - null_clientEpkId: идентификатор (STRING/UUID/INTEGER по схеме источника).
-- ============================================================================
SELECT 
    COUNT(*) as total,
    SUM(CASE WHEN var_caseId IS NULL THEN 1 ELSE 0 END) as null_caseId,
    SUM(CASE WHEN var_epkId IS NULL THEN 1 ELSE 0 END) as null_epkId,
    SUM(CASE WHEN var_fio IS NULL THEN 1 ELSE 0 END) as null_fio,
    SUM(CASE WHEN var_staticData_clientEpkId IS NULL THEN 1 ELSE 0 END) as null_clientEpkId
FROM hybrid_process_hybrid
