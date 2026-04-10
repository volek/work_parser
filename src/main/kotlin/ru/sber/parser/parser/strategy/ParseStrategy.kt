package ru.sber.parser.parser.strategy

import ru.sber.parser.model.BpmMessage

/**
 * Интерфейс стратегии парсинга BPM-сообщений для Apache Druid.
 * 
 * Определяет контракт для различных подходов к преобразованию
 * данных BPM-процессов в записи, готовые для загрузки в Druid.
 * 
 * Реализации:
 * ┌───────────────────┬────────────────────────────────────────┐
 * │ HybridStrategy    │ Один datasource с wide columns + blobs│
 * │ EavStrategy       │ Два datasource: события + переменные  │
 * │ CombinedStrategy  │ Два datasource: main + indexed vars   │
 * │ CompcomStrategy   │ Два datasource: compact main + indexed │
 * │ DefaultStrategy   │ Один datasource, все поля как колонки│
 * └───────────────────┴────────────────────────────────────────┘
 * 
 * Паттерн Strategy позволяет:
 * - Выбирать подход к хранению данных без изменения кода
 * - Легко добавлять новые стратегии
 * - Тестировать каждую стратегию изолированно
 * 
 * Использование:
 * ```kotlin
 * val strategy: ParseStrategy = HybridStrategy(fieldClassification)
 * val records = strategy.transform(bpmMessage)
 * druidClient.ingest(strategy.dataSourceName, records)
 * ```
 * 
 * @see HybridStrategy один datasource с колонками и блобами
 * @see EavStrategy EAV-схема с событиями и переменными
 * @see CombinedStrategy main record + индексная таблица
 * @see CompcomStrategy compact main record + индексная таблица
 * @see DefaultStrategy все поля сообщения как отдельные колонки (имя с точкой)
 */
interface ParseStrategy {
    /**
     * Основное имя datasource для загрузки данных.
     * 
     * Используется для:
     * - Создания datasource в Druid
     * - Отправки данных на ingestion endpoint
     * - Выполнения SQL-запросов
     * 
     * Примеры: "bpm_hybrid", "bpm_eav_events", "bpm_combined_main"
     */
    val dataSourceName: String
    
    /**
     * Дополнительные datasource (для стратегий с несколькими таблицами).
     * 
     * Используется EavStrategy и CombinedStrategy,
     * которые создают отдельные таблицы для переменных.
     * 
     * По умолчанию — пустой список (одна таблица).
     */
    val additionalDataSources: List<String>
        get() = emptyList()
    
    /**
     * Преобразует одно BPM-сообщение в список записей Druid.
     * 
     * Количество записей зависит от стратегии:
     * - HybridStrategy: 1 запись на сообщение
     * - EavStrategy: 1 событие + N переменных
     * - CombinedStrategy: 1 main + N indexed vars
     * - CompcomStrategy: 1 compact main + N indexed vars
     * - DefaultStrategy: 1 запись на сообщение (все поля — колонки)
     * 
     * Каждая запись — это Map, готовая для JSON-сериализации
     * и отправки в Druid ingestion API.
     * 
     * @param message BPM-сообщение для преобразования
     * @return Список записей в формате Map<String, Any?>
     */
    fun transform(message: BpmMessage): List<Map<String, Any?>>
    
    /**
     * Преобразует пакет сообщений с группировкой по datasource.
     * 
     * Оптимизирует batch-загрузку, объединяя записи
     * для каждого datasource в отдельный список.
     * 
     * Результат можно напрямую передать в DruidClient.ingestBatch().
     * 
     * @param messages Список BPM-сообщений
     * @return Map<dataSourceName, List<records>>
     */
    fun transformBatch(messages: List<BpmMessage>): Map<String, List<Map<String, Any?>>> {
        val allRecords = messages.flatMap { transform(it) }
        return groupByDataSource(allRecords)
    }
    
    /**
     * Группирует записи по datasource.
     * 
     * Базовая реализация помещает все записи в основной datasource.
     * Переопределяется в стратегиях с несколькими таблицами
     * для разделения записей по типу (например, events vs variables).
     * 
     * @param records Список всех записей
     * @return Map с группировкой по имени datasource
     */
    fun groupByDataSource(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return mapOf(dataSourceName to records)
    }
}
