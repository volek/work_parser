package ru.sber.parser.parser.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class DefaultStrategy : ParseStrategy {

    override val dataSourceName: String = DefaultRecord.DATA_SOURCE_NAME

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())

    private val flattener = VariableFlattener()

    override fun transform(message: BpmMessage): List<Map<String, Any?>> {
        val record = mutableMapOf<String, Any?>()

        // __time — обязательное поле для Druid (миллисекунды)
        record["__time"] = message.startDate.toInstant().toEpochMilli()

        // Поля верхнего уровня в snake_case
        record["id"] = message.id
        record["parent_instance_id"] = message.parentInstanceId
        record["root_instance_id"] = message.rootInstanceId
        record["process_id"] = message.processId
        record["process_definition_id"] = message.processDefinitionId
        record["resource_name"] = message.resourceName
        record["root_process_id"] = message.rootProcessId
        record["process_name"] = message.processName
        record["start_date"] = message.startDate.toInstant().toEpochMilli()
        record["end_date"] = message.endDate?.toInstant()?.toEpochMilli()
        record["state"] = message.state.toLong()
        record["business_key"] = message.businessKey
        record["version"] = message.version?.toLong()
        record["bam_project_id"] = message.bamProjectId
        record["ext_ids"] = message.extIds
        record["error"] = message.error
        record["module_id"] = message.moduleId
        record["engine_version"] = message.engineVersion
        record["engine_pod_name"] = message.enginePodName
        record["retry_count"] = message.retryCount?.toLong()
        record["owner_role"] = message.ownerRole
        record["idempotency_key"] = message.idempotencyKey
        record["operation"] = message.operation
        record["context_size"] = message.contextSize

        // node_instances — один столбец в виде JSON-строки
        record["node_instances"] = objectMapper.writeValueAsString(message.nodeInstances)

        // Переменные: сплющивание с путём через точку, префикс "variables."
        val flattened = flattener.flatten(message.variables)
        flattened.forEach { fv ->
            record["variables.${fv.path}"] = fv.value
        }

        return listOf(record)
    }
}
