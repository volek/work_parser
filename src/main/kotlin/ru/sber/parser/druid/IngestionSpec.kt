package ru.sber.parser.druid

/**
 * Спецификация индексации для создания datasource в Apache Druid.
 * 
 * Определяет схему данных, формат timestamp и параметры
 * сегментации для задачи index_parallel.
 * 
 * Структура ingestion spec:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ IngestionSpec                                                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ dataSchema:                                                         │
 * │   - dataSource: имя таблицы                                        │
 * │   - timestampSpec: колонка времени (__time)                        │
 * │   - dimensionsSpec: список колонок с типами                        │
 * │   - granularitySpec: сегментация (DAY) и гранулярность запросов   │
 * │                                                                     │
 * │ ioConfig:                                                           │
 * │   - inputSource: inline (данные в теле запроса) или file/kafka    │
 * │   - inputFormat: json                                              │
 * │                                                                     │
 * │ tuningConfig:                                                       │
 * │   - maxRowsPerSegment: размер сегмента                             │
 * │   - maxRowsInMemory: буфер в памяти                                │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * Использование:
 * ```kotlin
 * // Готовая спецификация для Hybrid-стратегии
 * val spec = IngestionSpec.forHybrid()
 * druidClient.createDataSource(spec)
 * 
 * // Кастомная спецификация
 * val customSpec = IngestionSpec(
 *     dataSource = "my_data",
 *     dimensions = listOf(
 *         DimensionSpec("field1", "string"),
 *         DimensionSpec("field2", "long")
 *     )
 * )
 * ```
 * 
 * @property dataSource Имя datasource в Druid
 * @property timestampColumn Колонка с timestamp (по умолчанию __time)
 * @property timestampFormat Формат времени: millis, iso, auto
 * @property dimensions Список спецификаций колонок
 * @property segmentGranularity Гранулярность сегментов: HOUR, DAY, MONTH
 * @property queryGranularity Гранулярность запросов: NONE, MINUTE, HOUR
 * @property rollup Агрегация при загрузке (false = сохранять все строки)
 * 
 * @see DimensionSpec описание колонки
 * @see DruidClient.createDataSource создание datasource
 */
data class IngestionSpec(
    val dataSource: String,
    val timestampColumn: String = "__time",
    val timestampFormat: String = "millis",
    val dimensions: List<DimensionSpec>,
    val segmentGranularity: String = "DAY",
    val queryGranularity: String = "NONE",
    val rollup: Boolean = false
) {
    /**
     * Преобразует спецификацию в Map для JSON-сериализации.
     * 
     * Формирует полную структуру index_parallel task spec.
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "type" to "index_parallel",
        "spec" to mapOf(
            "dataSchema" to mapOf(
                "dataSource" to dataSource,
                "timestampSpec" to mapOf(
                    "column" to timestampColumn,
                    "format" to timestampFormat
                ),
                "dimensionsSpec" to mapOf(
                    "dimensions" to dimensions.map { it.toMap() }
                ),
                "granularitySpec" to mapOf(
                    "type" to "uniform",
                    "segmentGranularity" to segmentGranularity,
                    "queryGranularity" to queryGranularity,
                    "rollup" to rollup
                )
            ),
            "ioConfig" to mapOf(
                "type" to "index_parallel",
                "inputSource" to mapOf(
                    "type" to "inline",
                    "data" to ""
                ),
                "inputFormat" to mapOf(
                    "type" to "json"
                )
            ),
            "tuningConfig" to mapOf(
                "type" to "index_parallel",
                "maxRowsPerSegment" to 5000000,
                "maxRowsInMemory" to 1000000
            )
        )
    )
    
    companion object {
        /**
         * Создаёт спецификацию для Hybrid-стратегии.
         * 
         * Включает все колонки HybridRecord:
         * - Метаданные процесса
         * - Hot-колонки (var_caseId, var_epkId, ...)
         * - Structured-колонки (var_staticData_*, var_epkData_*)
         * - JSON-блобы (node_instances_json, var_*_json)
         */
        fun forHybrid(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Hybrid.MAIN,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("process_name", "string"),
                DimensionSpec("state", "long"),
                DimensionSpec("module_id", "string"),
                DimensionSpec("business_key", "string"),
                DimensionSpec("root_instance_id", "string"),
                DimensionSpec("parent_instance_id", "string"),
                DimensionSpec("version", "long"),
                DimensionSpec("end_date", "long"),
                DimensionSpec("error", "string"),
                DimensionSpec("var_caseId", "string"),
                DimensionSpec("var_epkId", "string"),
                DimensionSpec("var_fio", "string"),
                DimensionSpec("var_ucpId", "string"),
                DimensionSpec("var_status", "string"),
                DimensionSpec("var_globalInstanceId", "string"),
                DimensionSpec("var_interactionId", "string"),
                DimensionSpec("var_interactionDate", "string"),
                DimensionSpec("var_theme", "string"),
                DimensionSpec("var_result", "string"),
                DimensionSpec("var_staticData_caseId", "string"),
                DimensionSpec("var_staticData_clientEpkId", "long"),
                DimensionSpec("var_staticData_casePublicId", "string"),
                DimensionSpec("var_staticData_statusCode", "string"),
                DimensionSpec("var_staticData_registrationTime", "long"),
                DimensionSpec("var_staticData_closedTime", "long"),
                DimensionSpec("var_staticData_classifierVersion", "long"),
                DimensionSpec("var_epkData_ucpId", "string"),
                DimensionSpec("var_epkData_clientStatus", "long"),
                DimensionSpec("var_epkData_gender", "long"),
                DimensionSpec("var_tracingHeaders_requestId", "string"),
                DimensionSpec("var_tracingHeaders_traceId", "string"),
                DimensionSpec("node_instances_json", "string"),
                DimensionSpec("var_epkData_json", "string"),
                DimensionSpec("var_staticData_json", "string"),
                DimensionSpec("var_answerGFL_json", "string"),
                DimensionSpec("var_other_json", "string")
            )
        )
        
        /**
         * Создаёт спецификацию для EAV-событий.
         * 
         * Таблица событий (метаданные процессов):
         * - process_id, process_name, state
         * - node_instances_json (сериализованные узлы)
         */
        fun forProcessEvents(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Eav.EVENTS,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("process_name", "string"),
                DimensionSpec("state", "long"),
                DimensionSpec("module_id", "string"),
                DimensionSpec("business_key", "string"),
                DimensionSpec("root_instance_id", "string"),
                DimensionSpec("parent_instance_id", "string"),
                DimensionSpec("version", "long"),
                DimensionSpec("end_date", "long"),
                DimensionSpec("error", "string"),
                DimensionSpec("node_instances_json", "string")
            )
        )
        
        /**
         * Создаёт спецификацию для EAV-переменных.
         * 
         * Таблица переменных (EAV-структура):
         * - process_id (Entity)
         * - var_path (Attribute)
         * - var_value (Value)
         * - var_type (тип данных)
         */
        fun forProcessVariables(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Eav.VARIABLES,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("var_path", "string"),
                DimensionSpec("var_value", "string"),
                DimensionSpec("var_type", "string")
            )
        )
        
        /**
         * Создаёт спецификацию для Combined-main.
         * 
         * Основная таблица с hot-колонками:
         * - Метаданные процесса
         * - Hot и structured колонки
         * - Cold-блоб (var_blob_json)
         */
        fun forProcessMain(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Combined.MAIN,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("process_name", "string"),
                DimensionSpec("state", "long"),
                DimensionSpec("module_id", "string"),
                DimensionSpec("business_key", "string"),
                DimensionSpec("root_instance_id", "string"),
                DimensionSpec("parent_instance_id", "string"),
                DimensionSpec("version", "long"),
                DimensionSpec("end_date", "long"),
                DimensionSpec("error", "string"),
                DimensionSpec("var_caseId", "string"),
                DimensionSpec("var_epkId", "string"),
                DimensionSpec("var_fio", "string"),
                DimensionSpec("var_ucpId", "string"),
                DimensionSpec("var_status", "string"),
                DimensionSpec("var_globalInstanceId", "string"),
                DimensionSpec("var_interactionId", "string"),
                DimensionSpec("var_interactionDate", "string"),
                DimensionSpec("var_theme", "string"),
                DimensionSpec("var_result", "string"),
                DimensionSpec("var_staticData_caseId", "string"),
                DimensionSpec("var_staticData_clientEpkId", "long"),
                DimensionSpec("var_staticData_casePublicId", "string"),
                DimensionSpec("var_staticData_statusCode", "string"),
                DimensionSpec("var_staticData_registrationTime", "long"),
                DimensionSpec("var_staticData_closedTime", "long"),
                DimensionSpec("var_staticData_classifierVersion", "long"),
                DimensionSpec("var_epkData_ucpId", "string"),
                DimensionSpec("var_epkData_clientStatus", "long"),
                DimensionSpec("var_epkData_gender", "long"),
                DimensionSpec("var_tracingHeaders_requestId", "string"),
                DimensionSpec("var_tracingHeaders_traceId", "string"),
                DimensionSpec("node_instances_json", "string"),
                DimensionSpec("var_blob_json", "string")
            )
        )
        
        /**
         * Создаёт спецификацию для Combined-indexed.
         * 
         * Индексная таблица warm-переменных:
         * - process_id (связь с main)
         * - var_category (epkData, staticData, ...)
         * - var_path (путь внутри категории)
         * - var_value, var_type
         */
        fun forProcessVariablesIndexed(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Combined.VARIABLES_INDEXED,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("var_category", "string"),
                DimensionSpec("var_path", "string"),
                DimensionSpec("var_value", "string"),
                DimensionSpec("var_type", "string")
            )
        )

        /**
         * Спецификация для Compcom-main (compact combined).
         * Основная таблица без cold-блоба: нет колонки var_blob_json.
         */
        fun forProcessMainCompact(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Compcom.MAIN_COMPACT,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("process_name", "string"),
                DimensionSpec("state", "long"),
                DimensionSpec("module_id", "string"),
                DimensionSpec("business_key", "string"),
                DimensionSpec("root_instance_id", "string"),
                DimensionSpec("parent_instance_id", "string"),
                DimensionSpec("version", "long"),
                DimensionSpec("end_date", "long"),
                DimensionSpec("error", "string"),
                DimensionSpec("var_caseId", "string"),
                DimensionSpec("var_epkId", "string"),
                DimensionSpec("var_fio", "string"),
                DimensionSpec("var_ucpId", "string"),
                DimensionSpec("var_status", "string"),
                DimensionSpec("var_globalInstanceId", "string"),
                DimensionSpec("var_interactionId", "string"),
                DimensionSpec("var_interactionDate", "string"),
                DimensionSpec("var_theme", "string"),
                DimensionSpec("var_result", "string"),
                DimensionSpec("var_staticData_caseId", "string"),
                DimensionSpec("var_staticData_clientEpkId", "long"),
                DimensionSpec("var_staticData_casePublicId", "string"),
                DimensionSpec("var_staticData_statusCode", "string"),
                DimensionSpec("var_staticData_registrationTime", "long"),
                DimensionSpec("var_staticData_closedTime", "long"),
                DimensionSpec("var_staticData_classifierVersion", "long"),
                DimensionSpec("var_epkData_ucpId", "string"),
                DimensionSpec("var_epkData_clientStatus", "long"),
                DimensionSpec("var_epkData_gender", "long"),
                DimensionSpec("var_tracingHeaders_requestId", "string"),
                DimensionSpec("var_tracingHeaders_traceId", "string"),
                DimensionSpec("node_instances_json", "string")
            )
        )

        /**
         * Спецификация для Compcom-indexed (compact combined).
         * Отдельный datasource от combined для изоляции данных стратегий.
         */
        fun forProcessVariablesIndexedCompact(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Compcom.VARIABLES_INDEXED,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("var_category", "string"),
                DimensionSpec("var_path", "string"),
                DimensionSpec("var_value", "string"),
                DimensionSpec("var_type", "string")
            )
        )
    }
}

/**
 * Спецификация колонки (dimension) в Apache Druid.
 * 
 * Определяет имя и тип колонки для индексации.
 * 
 * Поддерживаемые типы Druid:
 * - "string": текстовые данные, строки
 * - "long": целые числа (64-бит)
 * - "double": числа с плавающей точкой
 * 
 * Примечание: в Druid все колонки кроме __time являются
 * dimensions (измерениями), а не metrics (агрегатами),
 * когда rollup отключён.
 * 
 * @property name Имя колонки
 * @property type Тип данных: string, long, double
 */
data class DimensionSpec(
    val name: String,
    val type: String
) {
    /** Преобразует в Map для JSON-сериализации */
    fun toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "type" to type
    )
}
