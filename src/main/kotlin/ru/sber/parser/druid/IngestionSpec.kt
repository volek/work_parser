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
        fun forDefaultMain(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Default.MAIN,
            dimensions = listOf(
                DimensionSpec("id", "string"),
                DimensionSpec("process_id", "string"),
                DimensionSpec("process_name", "string"),
                DimensionSpec("start_date", "long"),
                DimensionSpec("state", "long")
            )
        )

        fun forDefaultArrays(): IngestionSpec = IngestionSpec(
            dataSource = DruidDataSources.Default.VARIABLES_ARRAY_INDEXED,
            dimensions = listOf(
                DimensionSpec("process_id", "string"),
                DimensionSpec("var_category", "string"),
                DimensionSpec("var_path", "string"),
                DimensionSpec("var_value", "string"),
                DimensionSpec("var_type", "string"),
                DimensionSpec("value_json", "string")
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
