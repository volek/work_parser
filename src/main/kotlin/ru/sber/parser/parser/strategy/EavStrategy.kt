package ru.sber.parser.parser.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.druid.ProcessEventRecord
import ru.sber.parser.model.druid.ProcessVariableRecord
import ru.sber.parser.parser.VariableFlattener

/**
 * EAV-стратегия (Entity-Attribute-Value) парсинга BPM-сообщений.
 * 
 * Создаёт ДВА datasource:
 * 1. bpm_eav_events — метаданные процессов (одна запись на процесс)
 * 2. bpm_eav_variables — переменные процессов (много записей на процесс)
 * 
 * Архитектура данных:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ bpm_eav_events                                                      │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ process_id │ __time │ process_name │ state │ node_instances_json   │
 * │ abc-123    │ ...    │ MyProcess    │ 2     │ [{"nodeId":...}]      │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ bpm_eav_variables                                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ process_id │ __time │ var_name          │ var_value │ var_type     │
 * │ abc-123    │ ...    │ caseId            │ 12345     │ STRING       │
 * │ abc-123    │ ...    │ staticData.status │ ACTIVE    │ STRING       │
 * │ abc-123    │ ...    │ amount            │ 1000.50   │ NUMBER       │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * EAV-модель:
 * - Entity = process_id (идентификатор процесса)
 * - Attribute = var_name (путь к переменной)
 * - Value = var_value (строковое значение)
 * 
 * Преимущества:
 * - Гибкость: любые переменные без изменения схемы
 * - Поиск по имени переменной: WHERE var_name = 'caseId'
 * - Компактность для процессов с малым числом переменных
 * 
 * Недостатки:
 * - Много строк на один процесс
 * - JOIN между events и variables для полной картины
 * - Все значения — строки (потеря типизации)
 * 
 * @see ProcessEventRecord модель записи события
 * @see ProcessVariableRecord модель записи переменной
 * @see VariableFlattener утилита сплющивания переменных
 */
class EavStrategy : ParseStrategy {
    
    /** Основной datasource — события (метаданные процессов) */
    override val dataSourceName: String = ProcessEventRecord.DATA_SOURCE_NAME
    
    /** Дополнительный datasource — переменные процессов */
    override val additionalDataSources: List<String> = listOf(ProcessVariableRecord.DATA_SOURCE_NAME)
    
    /** Jackson ObjectMapper для сериализации nodeInstances в JSON */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
    
    /** Утилита для сплющивания вложенных переменных */
    private val flattener = VariableFlattener()
    
    /**
     * Преобразует BPM-сообщение в записи EAV.
     * 
     * Генерирует:
     * - 1 запись ProcessEventRecord с метаданными процесса
     * - N записей ProcessVariableRecord (по одной на каждую переменную)
     * 
     * Записи помечаются полем _dataSource для последующей группировки.
     * 
     * @param message BPM-сообщение
     * @return Список записей с меткой datasource
     */
    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val records = mutableListOf<Map<String, Any?>>()
        
        // Создаём запись события
        val eventRecord = createEventRecord(message)
        records.add(eventRecord.toMap() + ("_dataSource" to ProcessEventRecord.DATA_SOURCE_NAME))
        
        // Создаём записи переменных
        val variableRecords = createVariableRecords(message)
        records.addAll(variableRecords.map { it.toMap() + ("_dataSource" to ProcessVariableRecord.DATA_SOURCE_NAME) })
        
        return records
    }
    
    /**
     * Оптимизированная batch-обработка сообщений.
     * 
     * Группирует записи по datasource сразу,
     * избегая повторной сортировки в groupByDataSource.
     * 
     * @param messages Список BPM-сообщений
     * @return Map<dataSourceName, List<records>>
     */
    override fun transformBatch(messages: List<BpmMessage>): Map<String, List<Map<String, Any?>>> {
        val eventRecords = mutableListOf<Map<String, Any?>>()
        val variableRecords = mutableListOf<Map<String, Any?>>()
        
        messages.forEach { message ->
            eventRecords.add(createEventRecord(message).toMap())
            variableRecords.addAll(createVariableRecords(message).map { it.toMap() })
        }
        
        return mapOf(
            ProcessEventRecord.DATA_SOURCE_NAME to eventRecords,
            ProcessVariableRecord.DATA_SOURCE_NAME to variableRecords
        )
    }
    
    /**
     * Группирует записи по datasource на основе поля _dataSource.
     * 
     * Используется для одиночных transform() вызовов,
     * удаляет служебное поле _dataSource из финальных записей.
     * 
     * @param records Записи с меткой _dataSource
     * @return Сгруппированные записи без служебного поля
     */
    override fun groupByDataSource(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return records.groupBy { it["_dataSource"] as String }
            .mapValues { (_, recs) -> recs.map { it - "_dataSource" } }
    }
    
    /**
     * Создаёт запись события из BPM-сообщения.
     * 
     * Содержит:
     * - Идентификаторы процесса
     * - Состояние и ошибки
     * - Сериализованные nodeInstances в JSON
     * 
     * @param message BPM-сообщение
     * @return ProcessEventRecord для datasource events
     */
    private fun createEventRecord(message: BpmMessage): ProcessEventRecord {
        return ProcessEventRecord(
            processId = message.id,
            timestamp = message.startDate.toInstant(),
            processName = message.processName,
            state = message.state,
            moduleId = message.moduleId,
            businessKey = message.businessKey,
            rootInstanceId = message.rootInstanceId,
            parentInstanceId = message.parentInstanceId,
            version = message.version,
            endDate = message.endDate?.toInstant(),
            error = message.error,
            nodeInstancesJson = objectMapper.writeValueAsString(message.nodeInstances)
        )
    }
    
    /**
     * Создаёт записи переменных из BPM-сообщения.
     * 
     * Использует VariableFlattener для преобразования
     * вложенной структуры variables в плоский список
     * пар (path, value, type).
     * 
     * @param message BPM-сообщение
     * @return Список ProcessVariableRecord для datasource variables
     */
    private fun createVariableRecords(message: BpmMessage): List<ProcessVariableRecord> {
        val timestamp = message.startDate.toInstant()
        val processId = message.id
        
        // Сплющиваем все переменные в плоский список
        val flattenedVars = flattener.flatten(message.variables)
        
        // Создаём EAV-запись для каждой переменной
        return flattenedVars.map { flatVar ->
            ProcessVariableRecord(
                processId = processId,
                timestamp = timestamp,
                varPath = flatVar.path,
                varValue = flatVar.value,
                varType = flatVar.type
            )
        }
    }
    
    companion object {
        /**
         * SQL-схема для datasource событий.
         * 
         * Используется SchemaGenerator для генерации DDL.
         */
        val EVENT_SCHEMA = mapOf(
            "process_id" to "VARCHAR",
            "__time" to "TIMESTAMP",
            "process_name" to "VARCHAR",
            "state" to "BIGINT",
            "module_id" to "VARCHAR",
            "business_key" to "VARCHAR",
            "root_instance_id" to "VARCHAR",
            "parent_instance_id" to "VARCHAR",
            "version" to "BIGINT",
            "end_date" to "TIMESTAMP",
            "error" to "VARCHAR",
            "node_instances_json" to "VARCHAR"
        )
        
        /**
         * SQL-схема для datasource переменных.
         * 
         * EAV-структура: process_id + var_path + var_value
         */
        val VARIABLE_SCHEMA = mapOf(
            "process_id" to "VARCHAR",
            "__time" to "TIMESTAMP",
            "var_path" to "VARCHAR",
            "var_value" to "VARCHAR",
            "var_type" to "VARCHAR"
        )
    }
}
