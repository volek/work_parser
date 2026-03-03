-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q30_json_staticData_closed.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: часто используемые атрибуты вынесены в плоские колонки, вложенные структуры хранятся в JSON.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: извлечение/анализ JSON-полей.
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 6) Упорядочивание результата через ORDER BY.
-- 7) Ограничение объёма выдачи через LIMIT.
--
-- Ожидаемые возвращаемые данные и формат:
-- - Тип результата: детальный набор (строки исходного уровня).
-- - Формат строк: одна строка результата на запись/группу согласно SELECT.
-- - Порядок столбцов: соответствует порядку полей в SELECT.
-- - Столбцы результата:
--   - process_id: идентификатор (STRING/UUID/INTEGER по схеме источника).
--   - process_name: текст/структура (STRING/JSON/ARRAY).
--   - var_staticData_registrationTime: дата/время (TIMESTAMP/DATE).
--   - var_staticData_closedTime: дата/время (TIMESTAMP/DATE).
--   - hours_to_close: тип определяется выражением в SELECT.
-- ============================================================================
SELECT 
    process_id,
    process_name,
    var_staticData_registrationTime,
    var_staticData_closedTime,
    CASE 
        WHEN var_staticData_registrationTime IS NOT NULL AND var_staticData_closedTime IS NOT NULL
        THEN TIMESTAMPDIFF(
            HOUR,
            TIME_PARSE(CAST(var_staticData_registrationTime AS VARCHAR)),
            TIME_PARSE(CAST(var_staticData_closedTime AS VARCHAR))
        )
        ELSE NULL
    END as hours_to_close
FROM process_hybrid
WHERE var_staticData_closedTime IS NOT NULL
ORDER BY __time DESC
LIMIT 50
