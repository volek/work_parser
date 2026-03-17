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
 * Компактная комбинированная стратегия (compcom — compact combined).
 *
 * Аналогична [CombinedStrategy], но **без сохранения cold blob в Druid**:
 * в основной таблице не создаётся колонка `var_blob_json`, холодные переменные не пишутся.
 *
 * Datasource:
 * - [PROCESS_MAIN_COMPACT] — основная запись (hot + structured, без cold blob)
 * - [ProcessVariableIndexedRecord.DATA_SOURCE_NAME] — индексные warm-переменные
 *
 * Поддерживает ограничение числа warm-переменных на сообщение [maxWarmVariables]
 * (например, 10..1010 с шагом 100 для вариативных тестов).
 *
 * @property fieldClassification Классификация полей (hot/warm)
 * @property maxWarmVariables Максимум записей в process_variables_indexed на одно сообщение (null = без ограничения)
 */
class CompcomStrategy(
    private val fieldClassification: FieldClassification,
    private val maxWarmVariables: Int? = null
) : ParseStrategy {

    override val dataSourceName: String = PROCESS_MAIN_COMPACT
    override val additionalDataSources: List<String> = listOf(ProcessVariableIndexedRecord.DATA_SOURCE_NAME)

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
    private val flattener = VariableFlattener()

    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val records = mutableListOf<Map<String, Any?>>()
        val mainRecord = createMainRecord(message)
        val mainMap = mainRecord.toMap() - "var_blob_json"
        records.add(mainMap + ("_dataSource" to PROCESS_MAIN_COMPACT))

        val variableRecords = createWarmVariableRecords(message)
        records.addAll(
            variableRecords.map {
                it.toMap() + ("_dataSource" to ProcessVariableIndexedRecord.DATA_SOURCE_NAME)
            }
        )
        return records
    }

    override fun transformBatch(messages: List<BpmMessage>): Map<String, List<Map<String, Any?>>> {
        val mainRecords = mutableListOf<Map<String, Any?>>()
        val variableRecords = mutableListOf<Map<String, Any?>>()
        messages.forEach { message ->
            mainRecords.add(createMainRecord(message).toMap() - "var_blob_json")
            variableRecords.addAll(createWarmVariableRecords(message).map { it.toMap() })
        }
        return mapOf(
            PROCESS_MAIN_COMPACT to mainRecords,
            ProcessVariableIndexedRecord.DATA_SOURCE_NAME to variableRecords
        )
    }

    override fun groupByDataSource(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return records.groupBy { it["_dataSource"] as String }
            .mapValues { (_, recs) -> recs.map { it - "_dataSource" } }
    }

    private fun createMainRecord(message: BpmMessage): ProcessMainRecord {
        val variables = message.variables
        val epkData = variables["epkData"] as? Map<*, *>
        val staticData = variables["staticData"] as? Map<*, *>
        val tracingHeaders = variables["tracingHeaders"] as? Map<*, *>
        val epkEntity = epkData?.get("epkEntity") as? Map<*, *>

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
            varBlobJson = null
        )
    }

    private fun createWarmVariableRecords(message: BpmMessage): List<ProcessVariableIndexedRecord> {
        val timestamp = message.startDate.toInstant()
        val processId = message.id
        val records = mutableListOf<ProcessVariableIndexedRecord>()
        fieldClassification.tier2WarmCategories.forEach { (category, paths) ->
            val categoryData = extractCategoryData(message.variables, category)
            if (categoryData != null) {
                val flattenedVars = flattener.flatten(
                    mapOf(category to categoryData),
                    prefix = ""
                )
                flattenedVars.forEach { flatVar ->
                    val pathWithoutPrefix = flatVar.path.removePrefix("$category.")
                    if (shouldIncludePath(pathWithoutPrefix, paths)) {
                        records.add(
                            ProcessVariableIndexedRecord(
                                processId = processId,
                                timestamp = timestamp,
                                varCategory = category,
                                varPath = pathWithoutPrefix,
                                varValue = flatVar.value,
                                varType = flatVar.type
                            )
                        )
                    }
                }
            }
        }
        val limit = maxWarmVariables ?: Int.MAX_VALUE
        return records.take(limit)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractCategoryData(variables: Map<String, Any?>, category: String): Map<String, Any?>? {
        return when (category) {
            "epkData" -> variables["epkData"] as? Map<String, Any?>
            "staticData" -> variables["staticData"] as? Map<String, Any?>
            "tracingHeaders" -> variables["tracingHeaders"] as? Map<String, Any?>
            "startAttributes" -> variables["startAttributes"] as? Map<String, Any?>
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

    private fun shouldIncludePath(path: String, configuredPaths: List<String>): Boolean {
        return configuredPaths.any { configPath ->
            when {
                configPath.contains("[*]") -> {
                    val pattern = configPath.replace("[*]", "\\[\\d+\\]")
                    path.matches(Regex(pattern))
                }
                else -> path == configPath || path.startsWith("$configPath.")
            }
        }
    }

    private fun getNestedString(map: Map<*, *>?, key: String): String? = map?.get(key)?.toString()

    private fun getNestedLong(map: Map<*, *>?, key: String): Long? {
        return when (val value = map?.get(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun getNestedInt(map: Map<*, *>?, key: String): Int? {
        return when (val value = map?.get(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

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
        const val PROCESS_MAIN_COMPACT = "process_main_compact"

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
            "node_instances_json" to "VARCHAR"
        )

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
