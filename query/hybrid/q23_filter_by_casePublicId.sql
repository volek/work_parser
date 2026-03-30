-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q23_filter_by_casePublicId.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `hybrid_process_hybrid`.
-- Назначение данного запроса: фильтрация записей по условиям.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: hybrid_process_hybrid.
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
--   - var_staticData_casePublicId: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - var_staticData_statusCode: категориальное значение (STRING/INTEGER).
--   - __time: дата/время (TIMESTAMP/DATE).
-- ============================================================================
SELECT 
    process_id,
    process_name,
    var_staticData_casePublicId,
    var_staticData_statusCode,
    __time
FROM hybrid_process_hybrid
WHERE var_staticData_casePublicId LIKE '2407%'
ORDER BY __time DESC
