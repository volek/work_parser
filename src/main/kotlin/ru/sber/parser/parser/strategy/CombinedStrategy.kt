package ru.sber.parser.parser.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.druid.ProcessMainRecord
import ru.sber.parser.model.druid.ProcessVariableIndexedRecord
import ru.sber.parser.parser.VariableFlattener
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Комбинированная стратегия парсинга BPM-сообщений.
 * 
 * Создаёт ДВА datasource с разделением по уровням доступа:
 * 1. bpm_combined_main — основная таблица с hot-колонками и cold-блобами
 * 2. bpm_combined_vars — индексная таблица для warm-переменных по категориям
 * 
 * Архитектура данных (Two-DataSource):
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ bpm_combined_main (одна запись на процесс)                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ Hot Columns:                                                        │
 * │   process_id, process_name, state, var_caseId, var_epkId, ...      │
 * │                                                                     │
 * │ Structured Columns:                                                 │
 * │   var_staticData_*, var_epkData_*, var_tracingHeaders_*            │
 * │                                                                     │
 * │ Cold Blob:                                                          │
 * │   var_blob_json — все редко используемые переменные                │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ bpm_combined_vars (индексная таблица, много записей на процесс)    │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ process_id │ __time │ var_category │ var_path    │ var_value │type │
 * │ abc-123    │ ...    │ epkData      │ epkEntity.* │ ...       │ ... │
 * │ abc-123    │ ...    │ staticData   │ caseId      │ 12345     │ STR │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * Уровни данных (Tiers):
 * - Tier 1 (Hot): колонки в main для прямого поиска WHERE var_caseId = 'X'
 * - Tier 2 (Warm): категоризированная таблица vars для гибкого поиска
 * - Tier 3 (Cold): JSON-блоб в main для полноты данных
 * 
 * Преимущества:
 * - Быстрый поиск по hot-полям без JOIN
 * - Гибкий поиск по warm-полям с категоризацией
 * - Полнота данных в cold-блобе
 * 
 * Недостатки:
 * - Сложность: два datasource вместо одного
 * - Дублирование: warm-данные есть и в колонках, и в vars
 * 
 * @property fieldClassification Конфигурация классификации полей
 * 
 * @see ProcessMainRecord модель основной записи
 * @see ProcessVariableIndexedRecord модель индексной записи
 * @see FieldClassification настройка hot/warm/cold полей
 */
class CombinedStrategy(
    private val fieldClassification: FieldClassification
) : ParseStrategy {
    
    /** Основной datasource — main record с hot-колонками */
    override val dataSourceName: String = ProcessMainRecord.DATA_SOURCE_NAME
    
    /** Дополнительный datasource — индексная таблица warm-переменных */
    override val additionalDataSources: List<String> = listOf(ProcessVariableIndexedRecord.DATA_SOURCE_NAME)
    
    /** Jackson ObjectMapper для сериализации JSON-блобов */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
    
    /** Утилита для сплющивания вложенных переменных по категориям */
    private val flattener = VariableFlattener()
    
    /**
     * Преобразует BPM-сообщение в записи Combined-модели.
     * 
     * Генерирует:
     * - 1 запись ProcessMainRecord с hot-колонками и cold-блобом
     * - N записей ProcessVariableIndexedRecord (warm-категории)
     * 
     * @param message BPM-сообщение
     * @return Список записей с меткой datasource
     */
    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val records = mutableListOf<Map<String, Any?>>()
        
        val mainRecord = createMainRecord(message)
        records.add(mainRecord.toMap() + ("_dataSource" to ProcessMainRecord.DATA_SOURCE_NAME))
        
        val variableRecords = createWarmVariableRecords(message)
        records.addAll(variableRecords.map { it.toMap() + ("_dataSource" to ProcessVariableIndexedRecord.DATA_SOURCE_NAME) })
        
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
        val mainRecords = mutableListOf<Map<String, Any?>>()
        val variableRecords = mutableListOf<Map<String, Any?>>()
        
        messages.forEach { message ->
            mainRecords.add(createMainRecord(message).toMap())
            variableRecords.addAll(createWarmVariableRecords(message).map { it.toMap() })
        }
        
        return mapOf(
            ProcessMainRecord.DATA_SOURCE_NAME to mainRecords,
            ProcessVariableIndexedRecord.DATA_SOURCE_NAME to variableRecords
        )
    }
    
    /**
     * Группирует записи по datasource на основе поля _dataSource.
     * 
     * @param records Записи с меткой _dataSource
     * @return Сгруппированные записи без служебного поля
     */
    override fun groupByDataSource(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return records.groupBy { it["_dataSource"] as String }
            .mapValues { (_, recs) -> recs.map { it - "_dataSource" } }
    }
    
    /**
     * Создаёт основную запись с hot-колонками и cold-блобом.
     * 
     * Извлекает:
     * - Hot поля (caseId, epkId, fio, ...) напрямую из variables
     * - Structured поля из epkData, staticData, tracingHeaders
     * - Cold данные в JSON-блоб
     * 
     * @param message BPM-сообщение
     * @return ProcessMainRecord для datasource main
     */
    private fun createMainRecord(message: BpmMessage): ProcessMainRecord {
        val variables = message.variables
        
        val epkData = variables["epkData"] as? Map<*, *>
        val staticData = variables["staticData"] as? Map<*, *>
        val tracingHeaders = variables["tracingHeaders"] as? Map<*, *>
        val epkEntity = epkData?.get("epkEntity") as? Map<*, *>
        
        val coldBlobs = extractColdBlobs(variables)
        
        return ProcessMainRecord(
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
            
            varCaseId = variables["caseId"]?.toString(),
            varEpkId = variables["epkId"]?.toString(),
            varFio = variables["fio"]?.toString(),
            varUcpId = variables["ucpId"]?.toString(),
            varStatus = variables["status"]?.toString(),
            varGlobalInstanceId = variables["globalInstanceId"]?.toString(),
            varInteractionId = variables["INTERACTION_ID"]?.toString(),
            varInteractionDate = variables["INTERACTION_DATE"]?.toString(),
            varTheme = variables["theme"]?.toString(),
            varResult = variables["result"]?.toString(),
            
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
            varBlobJson = if (coldBlobs.isNotEmpty()) objectMapper.writeValueAsString(coldBlobs) else null
        )
    }
    
    /**
     * Создаёт записи warm-переменных по категориям.
     * 
     * Обрабатывает каждую категорию из tier2WarmCategories:
     * 1. Извлекает данные категории из variables
     * 2. Сплющивает вложенную структуру
     * 3. Фильтрует по сконфигурированным путям
     * 4. Создаёт индексную запись для каждой переменной
     * 
     * @param message BPM-сообщение
     * @return Список ProcessVariableIndexedRecord для datasource vars
     */
    private fun createWarmVariableRecords(message: BpmMessage): List<ProcessVariableIndexedRecord> {
        val timestamp = message.startDate.toInstant()
        val processId = message.id
        val records = mutableListOf<ProcessVariableIndexedRecord>()
        
        // Обрабатываем каждую сконфигурированную категорию
        fieldClassification.tier2WarmCategories.forEach { (category, paths) ->
            val categoryData = extractCategoryData(message.variables, category)
            if (categoryData != null) {
                // Сплющиваем данные категории
                val flattenedVars = flattener.flatten(
                    mapOf(category to categoryData), 
                    prefix = ""
                )
                
                flattenedVars.forEach { flatVar ->
                    // Удаляем префикс категории из пути
                    val pathWithoutPrefix = flatVar.path.removePrefix("$category.")
                    
                    // Проверяем, входит ли путь в сконфигурированные
                    if (shouldIncludePath(pathWithoutPrefix, paths)) {
                        records.add(ProcessVariableIndexedRecord(
                            processId = processId,
                            timestamp = timestamp,
                            varCategory = category,
                            varPath = pathWithoutPrefix,
                            varValue = flatVar.value,
                            varType = flatVar.type
                        ))
                    }
                }
            }
        }
        
        return records
    }
    
    /**
     * Извлекает данные категории из переменных.
     * 
     * Поддерживаемые категории:
     * - epkData, staticData, tracingHeaders, startAttributes — прямое извлечение
     * - inputCC, inputDC — извлечение из startAttributes.attributes[*]
     * 
     * @param variables Карта переменных процесса
     * @param category Имя категории
     * @return Данные категории как Map или null
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractCategoryData(variables: Map<String, Any?>, category: String): Map<String, Any?>? {
        return when (category) {
            "epkData" -> variables["epkData"] as? Map<String, Any?>
            "staticData" -> variables["staticData"] as? Map<String, Any?>
            "tracingHeaders" -> variables["tracingHeaders"] as? Map<String, Any?>
            "startAttributes" -> variables["startAttributes"] as? Map<String, Any?>
            // Специальная обработка вложенных атрибутов
            "inputCC" -> {
                val startAttrs = variables["startAttributes"] as? Map<String, Any?>
                val attributes = startAttrs?.get("attributes") as? List<*>
                attributes?.mapNotNull { (it as? Map<*, *>)?.get("inputCC") }?.firstOrNull() as? Map<String, Any?>
            }
            "inputDC" -> {
                val startAttrs = variables["startAttributes"] as? Map<String, Any?>
                val attributes = startAttrs?.get("attributes") as? List<*>
                attributes?.mapNotNull { (it as? Map<*, *>)?.get("inputDC") }?.firstOrNull() as? Map<String, Any?>
            }
            else -> null
        }
    }
    
    /**
     * Проверяет, должен ли путь быть включён в индекс.
     * 
     * Поддерживает:
     * - Точное совпадение: path == configPath
     * - Вложенность: path.startsWith(configPath + ".")
     * - Wildcard массивов: configPath[*] matches path[N]
     * 
     * @param path Путь переменной после сплющивания
     * @param configuredPaths Сконфигурированные пути из tier2WarmCategories
     * @return true если путь должен быть индексирован
     */
    private fun shouldIncludePath(path: String, configuredPaths: List<String>): Boolean {
        return configuredPaths.any { configPath ->
            when {
                // Wildcard: items[*].name matches items[0].name, items[1].name, etc.
                configPath.contains("[*]") -> {
                    val pattern = configPath.replace("[*]", "\\[\\d+\\]")
                    path.matches(Regex(pattern))
                }
                // Точное совпадение или вложенность
                else -> path == configPath || path.startsWith("$configPath.")
            }
        }
    }
    
    /**
     * Извлекает cold-данные для JSON-блоба.
     * 
     * Собирает все переменные из tier3ColdBlobs
     * в единый Map для сериализации.
     * 
     * @param variables Карта переменных процесса
     * @return Map с cold-данными
     */
    private fun extractColdBlobs(variables: Map<String, Any?>): Map<String, Any?> {
        return fieldClassification.tier3ColdBlobs
            .mapNotNull { path -> variables[path]?.let { path to it } }
            .toMap()
    }
    
    // === Утилиты извлечения вложенных значений ===
    
    /** Извлекает строковое значение из вложенного Map */
    private fun getNestedString(map: Map<*, *>?, key: String): String? {
        return map?.get(key)?.toString()
    }
    
    /** Извлекает Long из вложенного Map с конвертацией */
    private fun getNestedLong(map: Map<*, *>?, key: String): Long? {
        return when (val value = map?.get(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
    
    /** Извлекает Int из вложенного Map с конвертацией */
    private fun getNestedInt(map: Map<*, *>?, key: String): Int? {
        return when (val value = map?.get(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
    
    /**
     * Парсит строку в Instant (timestamp).
     * 
     * Поддерживаемые форматы:
     * - ISO-8601 с offset: "2024-01-15T10:30:00+03:00"
     * - ISO-8601 UTC: "2024-01-15T07:30:00Z"
     * 
     * @param value Строка с датой
     * @return Instant или null при ошибке парсинга
     */
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
         * SQL-схема для основного datasource (main).
         * 
         * Содержит:
         * - Метаданные процесса (process_id, state, ...)
         * - Hot-колонки (var_caseId, var_epkId, ...)
         * - Structured-колонки (var_staticData_*, var_epkData_*)
         * - Cold-блоб (var_blob_json)
         */
        val MAIN_SCHEMA = mapOf(
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
            // Hot columns (Tier 1)
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
            // Structured columns (from staticData)
            "var_staticData_caseId" to "VARCHAR",
            "var_staticData_clientEpkId" to "BIGINT",
            "var_staticData_casePublicId" to "VARCHAR",
            "var_staticData_statusCode" to "VARCHAR",
            "var_staticData_registrationTime" to "TIMESTAMP",
            "var_staticData_closedTime" to "TIMESTAMP",
            "var_staticData_classifierVersion" to "BIGINT",
            // Structured columns (from epkData)
            "var_epkData_ucpId" to "VARCHAR",
            "var_epkData_clientStatus" to "BIGINT",
            "var_epkData_gender" to "BIGINT",
            // Structured columns (from tracingHeaders)
            "var_tracingHeaders_requestId" to "VARCHAR",
            "var_tracingHeaders_traceId" to "VARCHAR",
            // JSON blobs
            "node_instances_json" to "VARCHAR",
            "var_blob_json" to "VARCHAR"
        )
        
        /**
         * SQL-схема для индексного datasource (vars).
         * 
         * Категоризированная EAV-структура:
         * - var_category: группа переменных (epkData, staticData, ...)
         * - var_path: путь внутри категории
         * - var_value: строковое значение
         * - var_type: тип данных
         */
        val VARIABLE_INDEXED_SCHEMA = mapOf(
            "process_id" to "VARCHAR",
            "__time" to "TIMESTAMP",
            "var_category" to "VARCHAR",
            "var_path" to "VARCHAR",
            "var_value" to "VARCHAR",
            "var_type" to "VARCHAR"
        )
    }
}
