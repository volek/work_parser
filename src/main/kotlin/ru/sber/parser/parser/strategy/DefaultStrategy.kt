package ru.sber.parser.parser.strategy

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.druid.DruidDataSources
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.druid.DefaultRecord
import ru.sber.parser.parser.VariableFlattener

/**
 * Стратегия по умолчанию: парсинг всех полей входящего сообщения
 * с сохранением в Druid в виде отдельных колонок.
 *
 * Наименования колонок формируются из имён полей с разделителем «точка»:
 * - поля верхнего уровня — в snake_case (process_id, process_name, __time и т.д.);
 * - переменные процесса — с префиксом "variables." и путём через точку
 *   (например, variables.caseId, variables.staticData.clientEpkId).
 *
 * Один datasource, одна запись на сообщение.
 *
 * Реализации:
 * ┌───────────────────┬────────────────────────────────────────┐
 * │ HybridStrategy    │ Один datasource с wide columns + blobs │
 * │ EavStrategy       │ Два datasource: события + переменные  │
 * │ CombinedStrategy  │ Два datasource: main + indexed vars    │
 * │ DefaultStrategy   │ Один datasource, все поля как колонки   │
 * └───────────────────┴────────────────────────────────────────┘
 */
class DefaultStrategy(
    private val fieldClassification: FieldClassification = FieldClassification.default(),
    private val maxWarmVariables: Int? = null,
    private val arrayMaxDepth: Int? = null,
    private val arrayObjectJsonBlobEnabled: Boolean = false
) : ParseStrategy {

    override val dataSourceName: String = DefaultRecord.DATA_SOURCE_NAME
    override val additionalDataSources: List<String> = listOf(DruidDataSources.Default.VARIABLES_ARRAY_INDEXED)

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)

    private val flattener = VariableFlattener()

    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val byDs = transformBatch(listOf(message))
        val mainRecords = byDs[dataSourceName].orEmpty().map { it + ("_dataSource" to dataSourceName) }
        val arrayRecords = byDs[DruidDataSources.Default.VARIABLES_ARRAY_INDEXED].orEmpty()
            .map { it + ("_dataSource" to DruidDataSources.Default.VARIABLES_ARRAY_INDEXED) }
        return mainRecords + arrayRecords
    }

    override fun transformBatch(messages: List<BpmMessage>): Map<String, List<Map<String, Any?>>> {
        val mainRecords = mutableListOf<Map<String, Any?>>()
        val arrayRecords = mutableListOf<Map<String, Any?>>()
        messages.forEach { message ->
            val arrayRecordKeys = mutableSetOf<String>()
            mainRecords.add(createMainRecord(message, arrayRecordKeys, arrayRecords))
            if (arrayObjectJsonBlobEnabled) {
                createArrayObjectBlobRecords(message, arrayRecordKeys, arrayRecords)
            }
        }
        return mapOf(
            dataSourceName to mainRecords,
            DruidDataSources.Default.VARIABLES_ARRAY_INDEXED to arrayRecords
        )
    }

    private fun createMainRecord(
        message: BpmMessage,
        arrayRecordKeys: MutableSet<String>,
        arrayRecords: MutableList<Map<String, Any?>>
    ): Map<String, Any?> {
        val record = mutableMapOf<String, Any?>(
            "__time" to message.startDate.toInstant().toEpochMilli(),
            "id" to message.id,
            "process_id" to message.processId,
            "process_name" to message.processName,
            "start_date" to message.startDate.toInstant().toEpochMilli(),
            "state" to message.state.toLong()
        )
        val warmVariables = createWarmVariables(message)
        warmVariables.forEach { flatVar ->
            if (isArrayPath(flatVar.path)) {
                if (!withinArrayDepth(flatVar.path)) {
                    return@forEach
                }
                arrayRecords.add(
                    mapOf(
                        "process_id" to message.id,
                        "__time" to message.startDate.toInstant().toEpochMilli(),
                        "var_category" to categoryForPath(flatVar.path),
                        "var_path" to flatVar.path,
                        "var_value" to flatVar.value,
                        "var_type" to flatVar.type,
                        "value_json" to null
                    )
                )
                arrayRecordKeys.add(flatVar.path)
            } else {
                record["variables.${flatVar.path}"] = flatVar.value
            }
        }
        return record
    }

    private fun createWarmVariables(message: BpmMessage): List<VariableFlattener.FlattenedVariable> {
        val warmVariables = mutableListOf<VariableFlattener.FlattenedVariable>()
        fieldClassification.tier2WarmCategories.forEach { (category, paths) ->
            val categoryData = extractCategoryData(message.variables, category) ?: return@forEach
            val flattened = flattener.flatten(mapOf(category to categoryData))
            flattened.forEach { flatVar ->
                val pathWithoutPrefix = flatVar.path.removePrefix("$category.")
                if (shouldIncludePath(pathWithoutPrefix, paths)) {
                    warmVariables.add(
                        VariableFlattener.FlattenedVariable(
                            path = flatVar.path,
                            value = flatVar.value,
                            type = flatVar.type
                        )
                    )
                }
            }
        }
        val limit = maxWarmVariables ?: Int.MAX_VALUE
        return warmVariables.take(limit)
    }

    private fun createArrayObjectBlobRecords(
        message: BpmMessage,
        arrayRecordKeys: MutableSet<String>,
        arrayRecords: MutableList<Map<String, Any?>>
    ) {
        val blobRecords = mutableListOf<Map<String, Any?>>()
        fieldClassification.tier2WarmCategories.forEach { (category, _) ->
            val categoryData = extractCategoryData(message.variables, category) ?: return@forEach
            collectArrayObjectBlobs(
                value = categoryData,
                currentPath = category,
                arrayDepth = 0,
                inArrayContext = false,
                processId = message.id,
                timestamp = message.startDate.toInstant().toEpochMilli(),
                result = blobRecords
            )
        }
        blobRecords.forEach { record ->
            val path = record["var_path"]?.toString() ?: return@forEach
            if (arrayRecordKeys.add(path)) {
                arrayRecords.add(record)
            }
        }
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

    private fun isArrayPath(path: String): Boolean = path.contains(Regex("\\[\\d+\\]"))

    private fun withinArrayDepth(path: String): Boolean {
        val limit = arrayMaxDepth ?: return true
        val depth = Regex("\\[\\d+\\]").findAll(path).count()
        return depth <= limit
    }

    private fun categoryForPath(path: String): String = path.substringBefore(".")

    @Suppress("UNCHECKED_CAST")
    private fun collectArrayObjectBlobs(
        value: Any?,
        currentPath: String,
        arrayDepth: Int,
        inArrayContext: Boolean,
        processId: String,
        timestamp: Long,
        result: MutableList<Map<String, Any?>>
    ) {
        val maxDepth = arrayMaxDepth
        if (maxDepth != null && arrayDepth > maxDepth) {
            return
        }
        when (value) {
            is List<*> -> {
                value.forEachIndexed { index, item ->
                    collectArrayObjectBlobs(
                        value = item,
                        currentPath = "$currentPath[$index]",
                        arrayDepth = arrayDepth + 1,
                        inArrayContext = true,
                        processId = processId,
                        timestamp = timestamp,
                        result = result
                    )
                }
            }
            is Map<*, *> -> {
                if (inArrayContext) {
                    result.add(
                        mapOf(
                            "process_id" to processId,
                            "__time" to timestamp,
                            "var_category" to categoryForPath(currentPath),
                            "var_path" to currentPath,
                            "var_value" to null,
                            "var_type" to VariableFlattener.TYPE_JSON,
                            "value_json" to objectMapper.writeValueAsString(value as Map<String, Any?>)
                        )
                    )
                }
                value.forEach { (key, nested) ->
                    if (key != null) {
                        collectArrayObjectBlobs(
                            value = nested,
                            currentPath = "$currentPath.$key",
                            arrayDepth = arrayDepth,
                            inArrayContext = inArrayContext,
                            processId = processId,
                            timestamp = timestamp,
                            result = result
                        )
                    }
                }
            }
        }
    }

    companion object {
        val ARRAY_VARIABLE_SCHEMA = mapOf(
            "process_id" to "VARCHAR",
            "__time" to "TIMESTAMP",
            "var_category" to "VARCHAR",
            "var_path" to "VARCHAR",
            "var_value" to "VARCHAR",
            "var_type" to "VARCHAR",
            "value_json" to "VARCHAR"
        )
    }
}
