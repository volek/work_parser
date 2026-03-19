-- ============================================================================
-- Описание запроса:
-- Файл: `hybrid/q54_json_epkData_birth_dates.sql`.
-- Стратегия: Hybrid (Flat + JSON).
-- Модель стратегии: hot/warm поля в колонках + JSON-блобы для полноты данных.
-- Типовые таблицы стратегии: обычно `process_hybrid`.
-- Назначение данного запроса: извлечение дат рождения/смерти из полного JSON-блоба epkData (var_epkData_json).
--
-- Логика выполнения запроса:
-- 1) Выбор источника данных: process_hybrid.
-- 3) Применение фильтров WHERE для отбора релевантных строк.
-- 7) Ограничение объёма выдачи через LIMIT.
-- ============================================================================
SELECT
    process_id,
    JSON_VALUE(var_epkData_json, '$.epkEntity.birthDate') AS birth_date,
    JSON_VALUE(var_epkData_json, '$.epkEntity.deathDate') AS death_date,
    var_fio
FROM process_main
WHERE var_epkData_json IS NOT NULL
LIMIT 50

