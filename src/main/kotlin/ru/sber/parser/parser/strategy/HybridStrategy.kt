package ru.sber.parser.parser.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.config.FieldType
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.druid.HybridRecord
import ru.sber.parser.parser.VariableFlattener
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Гибридная стратегия парсинга BPM-сообщений.
 * 
 * Создаёт ОДИН datasource с комбинацией:
 * - Wide columns для часто запрашиваемых полей (hot tier)
 * - Категоризированных колонок для структурированных данных (warm tier)
 * - JSON-блобов для редко используемых данных (cold tier)
 * 
 * Архитектура данных:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                      bpm_hybrid datasource                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ Wide Columns (Hot):                                                 │
 * │   process_id, process_name, state, var_caseId, var_epkId, ...      │
 * │                                                                     │
 * │ Structured Columns (Warm):                                          │
 * │   var_staticData_* — поля из staticData                            │
 * │   var_epkData_*    — поля из epkData.epkEntity                     │
 * │   var_tracingHeaders_* — заголовки трассировки                     │
 * │                                                                     │
 * │ JSON Blobs (Cold):                                                  │
 * │   node_instances_json — все узлы процесса                          │
 * │   var_epkData_json    — полный объект epkData                      │
 * │   var_staticData_json — полный объект staticData                   │
 * │   var_answerGFL_json  — ответы системы ГФЛ                         │
 * │   var_other_json      — все остальные переменные                   │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * Преимущества:
 * - Один datasource упрощает запросы и администрирование
 * - Hot поля индексируются для быстрого поиска
 * - Cold данные не замедляют индексацию
 * - Полнота данных сохраняется в JSON-блобах
 * 
 * Недостатки:
 * - Wide table может иметь много пустых колонок
 * - Добавление новых hot-полей требует пересоздания datasource
 * 
 * @property fieldClassification Конфигурация классификации полей
 * 
 * @see HybridRecord модель данных для этой стратегии
 * @see FieldClassification настройка hot/warm/cold полей
 */
class HybridStrategy(
    private val fieldClassification: FieldClassification
) : ParseStrategy {
    
    /** Имя datasource в Druid */
    override val dataSourceName: String = HybridRecord.DATA_SOURCE_NAME
    
    /** Jackson ObjectMapper для JSON-сериализации блобов */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
    
    /** Утилита для работы с вложенными переменными */
    private val flattener = VariableFlattener()
    
    /**
     * Трансформирует BPM-сообщение в одну Map-запись для Druid.
     * 
     * @param message Входное BPM-сообщение
     * @return Список с одной Map-записью (HybridRecord.toMap())
     */
    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val record = createHybridRecord(message)
        return listOf(record.toMap())
    }
    
    /**
     * Создаёт HybridRecord из BPM-сообщения.
     * 
     * Логика извлечения данных:
     * 1. Извлекаем hot-поля (top-level переменные из tier1)
     * 2. Извлекаем structured данные (epkData, staticData, tracingHeaders)
     * 3. Сериализуем cold-блобы в JSON
     * 4. Собираем остальные переменные в var_other_json
     */
    private fun createHybridRecord(message: BpmMessage): HybridRecord {
        val variables = message.variables
        
        val epkData = variables["epkData"] as? Map<*, *>
        val staticData = variables["staticData"] as? Map<*, *>
        val tracingHeaders = variables["tracingHeaders"] as? Map<*, *>
        val epkEntity = epkData?.get("epkEntity") as? Map<*, *>
        
        val hotFields = extractHotFields(variables)
        val coldBlobs = extractColdBlobs(variables)
        val otherVars = extractOtherVariables(variables)
        
        return HybridRecord(
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
            
            varCaseId = hotFields["caseId"]?.toString(),
            varEpkId = hotFields["epkId"]?.toString(),
            varFio = hotFields["fio"]?.toString(),
            varUcpId = hotFields["ucpId"]?.toString(),
            varStatus = hotFields["status"]?.toString(),
            varGlobalInstanceId = hotFields["globalInstanceId"]?.toString(),
            varInteractionId = hotFields["INTERACTION_ID"]?.toString(),
            varInteractionDate = hotFields["INTERACTION_DATE"]?.toString(),
            varTheme = hotFields["theme"]?.toString(),
            varResult = hotFields["result"]?.toString(),
            
            varStaticDataCaseId = getNestedString(staticData, "caseId"),
            varStaticDataClientEpkId = getNestedLong(staticData, "clientEpkId"),
            varStaticDataCasePublicId = getNestedString(staticData, "casePublicId"),
            varStaticDataStatusCode = getNestedString(staticData, "statusCode"),
            varStaticDataRegistrationTime = parseTimestamp(getNestedString(staticData, "registrationTime")),
            varStaticDataClosedTime = parseTimestamp(getNestedString(staticData, "closedTime")),
            varStaticDataClassifierVersion = getNestedInt(staticData, "classifierVersion"),
            
            varEpkDataUcpId = getNestedString(epkEntity, "ucpId"),
            varEpkDataClientStatus = getNestedInt(epkEntity, "clientStatus"),
            varEpkDataGender = getNestedInt(epkEntity, "gender"),
            
            varTracingHeadersRequestId = getNestedString(tracingHeaders, "x-request-id"),
            varTracingHeadersTraceId = getNestedString(tracingHeaders, "x-b3-traceid"),
            
            nodeInstancesJson = objectMapper.writeValueAsString(message.nodeInstances),
            varEpkDataJson = if (epkData != null) objectMapper.writeValueAsString(epkData) else null,
            varStaticDataJson = if (staticData != null) objectMapper.writeValueAsString(staticData) else null,
            varAnswerGFLJson = coldBlobs["answerGFL"],
            varOtherJson = if (otherVars.isNotEmpty()) objectMapper.writeValueAsString(otherVars) else null
        )
    }
    
    /** Извлекает hot-поля (tier1) из переменных — только top-level ключи */
    private fun extractHotFields(variables: Map<String, Any?>): Map<String, Any?> {
        val hotPaths = fieldClassification.tier1HotColumns
            .filter { !it.sourcePath.contains(".") }
            .map { it.sourcePath }
        
        return variables.filterKeys { it in hotPaths }
    }
    
    /** Извлекает cold-блобы (tier3) и сериализует их в JSON */
    private fun extractColdBlobs(variables: Map<String, Any?>): Map<String, String?> {
        val coldPaths = fieldClassification.tier3ColdBlobs
        return coldPaths.associateWith { path ->
            variables[path]?.let { objectMapper.writeValueAsString(it) }
        }
    }
    
    /** Извлекает переменные, не попавшие в hot/cold/structured категории */
    private fun extractOtherVariables(variables: Map<String, Any?>): Map<String, Any?> {
        val hotPaths = fieldClassification.tier1HotColumns.map { it.sourcePath.split(".").first() }.toSet()
        val coldPaths = fieldClassification.tier3ColdBlobs.toSet()
        val structuredPaths = setOf("epkData", "staticData", "tracingHeaders", "startAttributes")
        
        val excludePaths = hotPaths + coldPaths + structuredPaths
        
        return variables.filterKeys { it !in excludePaths }
    }
    
    // === Утилиты извлечения типизированных значений из Map ===
    
    /** Извлекает строковое значение по ключу */
    private fun getNestedString(map: Map<*, *>?, key: String): String? {
        return map?.get(key)?.toString()
    }
    
    /** Извлекает Long значение (из Number или String) */
    private fun getNestedLong(map: Map<*, *>?, key: String): Long? {
        return when (val value = map?.get(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
    
    /** Извлекает Int значение (из Number или String) */
    private fun getNestedInt(map: Map<*, *>?, key: String): Int? {
        return when (val value = map?.get(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
    
    /** Парсит timestamp из ISO-8601 или Instant строки */
    private fun parseTimestamp(value: String?): Instant? {
        if (value == null) return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e: Exception) {
            try {
                Instant.parse(value)
            } catch (e2: Exception) {
                null
            }
        }
    }
    
    companion object {
        /**
         * Схема колонок для создания datasource в Druid.
         * 
         * Используется для:
         * - Генерации DDL в SchemaGenerator
         * - Создания IngestionSpec
         * - Документации структуры данных
         */
        val SCHEMA = mapOf(
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
            "var_caseId" to "VARCHAR",
            "var_epkId" to "VARCHAR",
            "var_fio" to "VARCHAR",
            "var_ucpId" to "VARCHAR",
            "var_status" to "VARCHAR",
            "var_globalInstanceId" to "VARCHAR",
            "var_interactionId" to "VARCHAR",
            "var_interactionDate" to "VARCHAR",
            "var_theme" to "VARCHAR",
            "var_result" to "VARCHAR",
            "var_staticData_caseId" to "VARCHAR",
            "var_staticData_clientEpkId" to "BIGINT",
            "var_staticData_casePublicId" to "VARCHAR",
            "var_staticData_statusCode" to "VARCHAR",
            "var_staticData_registrationTime" to "TIMESTAMP",
            "var_staticData_closedTime" to "TIMESTAMP",
            "var_staticData_classifierVersion" to "BIGINT",
            "var_epkData_ucpId" to "VARCHAR",
            "var_epkData_clientStatus" to "BIGINT",
            "var_epkData_gender" to "BIGINT",
            "var_tracingHeaders_requestId" to "VARCHAR",
            "var_tracingHeaders_traceId" to "VARCHAR",
            "node_instances_json" to "VARCHAR",
            "var_epkData_json" to "VARCHAR",
            "var_staticData_json" to "VARCHAR",
            "var_answerGFL_json" to "VARCHAR",
            "var_other_json" to "VARCHAR"
        )
    }
}
