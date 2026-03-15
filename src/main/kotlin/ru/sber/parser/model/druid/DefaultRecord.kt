package ru.sber.parser.model.druid

/**
 * Модель записи стратегии Default для Apache Druid.
 *
 * Все поля входящего BPM-сообщения сохраняются в одном datasource в виде отдельных колонок.
 * Наименования колонок формируются из имён полей с разделителем «точка»:
 *
 * - **Поля верхнего уровня** — в snake_case: `id`, `process_id`, `process_name`, `state`,
 *   `__time`, `start_date`, `end_date`, `module_id`, `business_key`, `node_instances` (JSON-строка)
 *   и остальные поля сообщения.
 *
 * - **Переменные процесса** — с префиксом `variables.` и путём через точку:
 *   `variables.caseId`, `variables.staticData.clientEpkId`, `variables.epkData.epkEntity.ucpId` и т.д.
 *
 * Схема колонок динамическая: набор колонок определяется данными (разные сообщения могут
 * содержать разные наборы переменных). Одна запись на сообщение.
 *
 * @see ru.sber.parser.parser.strategy.DefaultStrategy стратегия, формирующая записи
 */
object DefaultRecord {
    /**
     * Название источника данных в Apache Druid для стратегии Default.
     */
    const val DATA_SOURCE_NAME = "process_default"
}
