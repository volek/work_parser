package ru.sber.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import ru.sber.parser.config.AppConfig
import ru.sber.parser.druid.DruidClient
import ru.sber.parser.generator.MessageGenerator
import ru.sber.parser.metastore.PostgresSchemaMetastoreRepository
import ru.sber.parser.metastore.SchemaRegistryService
import ru.sber.parser.parser.MessageParser
import ru.sber.parser.parser.strategy.CombinedStrategy
import ru.sber.parser.parser.strategy.CompcomStrategy
import ru.sber.parser.parser.strategy.DefaultStrategy
import ru.sber.parser.parser.strategy.EavStrategy
import ru.sber.parser.parser.strategy.HybridStrategy
import ru.sber.parser.parser.strategy.ParseStrategy
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Логгер для главного модуля приложения.
 */
private val logger = LoggerFactory.getLogger("Application")
private val supportedStrategies = listOf("hybrid", "eav", "combined", "compcom", "default")

/**
 * ObjectMapper для вспомогательного анализа (размеры структур и т.п.).
 */
private val analysisObjectMapper: ObjectMapper = jacksonObjectMapper()

private fun createStrategyOrNull(
    strategyName: String,
    config: AppConfig
): ParseStrategy? {
    val warmLimit = config.parserConfig?.effectiveWarmVariablesLimit()
    return when (strategyName.lowercase()) {
        "hybrid" -> HybridStrategy(config.fieldClassification)
        "eav" -> EavStrategy()
        "combined" -> CombinedStrategy(config.fieldClassification, warmLimit)
        "compcom" -> CompcomStrategy(config.fieldClassification, warmLimit)
        "default" -> DefaultStrategy()
        else -> null
    }
}

/**
 * Точка входа в приложение BPM Message Parser.
 * 
 * Приложение предназначено для:
 * 1. Парсинга JSON-сообщений из Kafka с данными бизнес-процессов
 * 2. Трансформации данных согласно выбранной стратегии
 * 3. Загрузки данных в Apache Druid для аналитики
 * 
 * Поддерживаемые команды:
 * - generate: генерация тестовых сообщений
 * - parse: парсинг и трансформация сообщений
 * - query: выполнение SQL-запросов к Druid
 * - help: справка по использованию
 * 
 * @param args Аргументы командной строки
 */
fun main(args: Array<String>) = runBlocking {
    logger.info("BPM Message Parser for Apache Druid starting...")
    
    // Загружаем конфигурацию из файла config.yaml и переменных окружения
    val config = AppConfig.load()
    val parser = MessageParser()
    
    // Обработка команд через pattern matching
    when (val command = args.firstOrNull() ?: "help") {
        
        // ==========================================
        // КОМАНДА: generate
        // Генерация тестовых JSON-сообщений
        // ==========================================
        "generate" -> {
            val positionalArgs = args.filter { !it.startsWith("--") }
            val outputDir = File(positionalArgs.getOrElse(1) { "messages" })
            val count = positionalArgs.getOrElse(2) { "500" }.toIntOrNull()?.coerceIn(1, 10000) ?: 500
            logger.info("Generating $count test messages...")
            val generator = MessageGenerator()
            outputDir.mkdirs()
            generator.generateAll(outputDir, count)
            logger.info("Generated $count messages saved to: ${outputDir.absolutePath}")
        }
        
        // ==========================================
        // КОМАНДА: parse
        // Парсинг сообщений с выбранной стратегией
        // ==========================================
        "parse" -> {
            // Название стратегии: hybrid, eav, combined, compcom или default
            val strategyName: String = args.getOrElse(1) { "hybrid" }
            // Директория с входными JSON-файлами (пропускаем флаги, начинающиеся с --)
            val positionalArgs = args.filter { !it.startsWith("--") }
            val inputDir = File(positionalArgs.getOrElse(2) { "messages" })
            
            val warmLimit = config.parserConfig?.effectiveWarmVariablesLimit()
            // Выбор стратегии трансформации данных
            val strategy = createStrategyOrNull(strategyName, config)
            if (strategy == null) {
                logger.error(
                    "Unknown strategy: {}. Use: {}",
                    strategyName,
                    supportedStrategies.joinToString(", ")
                )
                return@runBlocking
            }
            
            logger.info(
                "ANALYSIS|component=app.strategy|stage=catalog|strategies_count={}|strategies={}",
                supportedStrategies.size,
                supportedStrategies.joinToString(",")
            )
            logger.info("Parsing messages with strategy: $strategyName")
            logger.info(
                "ANALYSIS|component=app.strategy|strategy={}|stage=params|warm_variables_limit_effective={}",
                strategyName,
                warmLimit ?: "null"
            )
            logger.info(
                "TEMP_PERF|component=app.strategy|operation=selection|strategy={}|strategies_count={}",
                strategyName,
                supportedStrategies.size
            )

            // Аналитическое логирование схемы данных и SQL-запросов для выбранной стратегии.
            // Это статический анализ: какие datasources, какие колонки и какие SQL-файлы привязаны к стратегии.
            try {
                // Определяем основную и дополнительные схемы колонок Druid.
                val primarySchema: Map<String, String>?
                val secondarySchemas: Map<String, Map<String, String>>
                when (strategy) {
                    is HybridStrategy -> {
                        primarySchema = ru.sber.parser.parser.strategy.HybridStrategy.SCHEMA
                        secondarySchemas = emptyMap()
                    }
                    is EavStrategy -> {
                        primarySchema = ru.sber.parser.parser.strategy.EavStrategy.EVENT_SCHEMA
                        secondarySchemas = mapOf(
                            "variables" to ru.sber.parser.parser.strategy.EavStrategy.VARIABLE_SCHEMA
                        )
                    }
                    is CombinedStrategy -> {
                        primarySchema = ru.sber.parser.parser.strategy.CombinedStrategy.MAIN_SCHEMA
                        secondarySchemas = mapOf(
                            "variables_indexed" to ru.sber.parser.parser.strategy.CombinedStrategy.VARIABLE_INDEXED_SCHEMA
                        )
                    }
                    is CompcomStrategy -> {
                        primarySchema = ru.sber.parser.parser.strategy.CompcomStrategy.MAIN_SCHEMA
                        secondarySchemas = mapOf(
                            "variables_indexed" to ru.sber.parser.parser.strategy.CompcomStrategy.VARIABLE_INDEXED_SCHEMA
                        )
                    }
                    is DefaultStrategy -> {
                        // Для DefaultStrategy схема динамическая, но у модели есть базовый набор полей.
                        primarySchema = null
                        secondarySchemas = emptyMap()
                    }
                    else -> {
                        primarySchema = null
                        secondarySchemas = emptyMap()
                    }
                }

                val schemaJson = primarySchema?.let { analysisObjectMapper.writeValueAsString(it) }
                val secondarySchemasJson = if (secondarySchemas.isNotEmpty()) {
                    analysisObjectMapper.writeValueAsString(secondarySchemas)
                } else {
                    null
                }

                // Анализ SQL‑запросов по текущей стратегии: файлы, суммарный размер и количество.
                val queryDir = File("query/${strategyName.lowercase()}")
                val sqlFiles = if (queryDir.exists()) {
                    queryDir.walkTopDown().filter { it.isFile && it.extension == "sql" }.toList()
                } else {
                    emptyList()
                }
                val totalQueryFiles = sqlFiles.size
                val totalQueryBytes = sqlFiles.sumOf { it.length() }

                logger.info(
                    "ANALYSIS|component=app.strategy|strategy={}|stage=metadata|primary_datasource={}|additional_datasources={}|primary_schema_json={}|secondary_schemas_json={}|query_dir={}|query_files_count={}|query_files_bytes_total={}",
                    strategyName,
                    strategy.dataSourceName,
                    strategy.additionalDataSources.joinToString(","),
                    schemaJson,
                    secondarySchemasJson,
                    queryDir.absolutePath,
                    totalQueryFiles,
                    totalQueryBytes
                )
            } catch (ex: Exception) {
                logger.warn("ANALYSIS|component=app.strategy|strategy=$strategyName|stage=metadata|status=failed|reason=${ex.message}")
            }

            // Метрика: старт полного цикла команды parse.
            val parseCommandStartNs = System.nanoTime()
            // Метрика: старт поиска входных JSON-файлов.
            val scanStartNs = System.nanoTime()
            val jsonFiles = inputDir.listFiles { f: File -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
            // Метрика: длительность сканирования директории с входными файлами.
            val scanNs = System.nanoTime() - scanStartNs

            // Метрика: суммарный размер всех входных JSON-файлов в байтах.
            val totalInputBytes = jsonFiles.sumOf { it.length() }
            // Метрика: суммарная длительность чтения всех JSON-файлов с диска.
            var totalReadNs = 0L
            // Метрика: суммарная длительность JSON-десериализации всех файлов.
            var totalParseNs = 0L
            // Метрика: минимальная длительность полного цикла обработки одного файла.
            var minPerFileNs = Long.MAX_VALUE
            // Метрика: максимальная длительность полного цикла обработки одного файла.
            var maxPerFileNs = 0L

            // Парсим все JSON-файлы из входной директории с детальными временными метриками по каждому файлу.
            val schemaRegistryRepo = if (config.schemaMetastore.enabled) {
                val pg = config.schemaMetastore.postgres
                PostgresSchemaMetastoreRepository(
                    jdbcUrl = pg.effectiveJdbcUrl(),
                    username = pg.user,
                    password = pg.password,
                    maxPoolSize = pg.maxPoolSize,
                    connectionTimeoutMs = pg.connectionTimeoutMs
                ).also {
                    it.initializeSchema()
                    logger.info("Schema metastore is enabled, PostgreSQL repository initialized")
                }
            } else {
                logger.info("Schema metastore is disabled, running baseline pipeline")
                null
            }
            val schemaRegistryService = schemaRegistryRepo?.let(::SchemaRegistryService)
            val messages = try {
                jsonFiles.map { file: File ->
                    // Метрика: старт полного цикла обработки конкретного файла.
                    val fileTotalStartNs = System.nanoTime()
                    // Метрика: старт чтения файла с диска.
                    val readStartNs = System.nanoTime()
                    val content = file.readText()
                    // Метрика: длительность чтения файла.
                    val readNs = System.nanoTime() - readStartNs
                    totalReadNs += readNs
                    // Метрика: старт парсинга JSON в объект BpmMessage.
                    val parseStartNs = System.nanoTime()
                    val parsed = parser.parse(content)
                    // Метрика: длительность парсинга одного JSON-файла.
                    val parseNs = System.nanoTime() - parseStartNs
                    totalParseNs += parseNs

                    schemaRegistryService?.registerMessageSchema(
                        rawMessage = content,
                        message = parsed,
                        source = file.absolutePath
                    )

                    // Метрика: полная длительность обработки файла (read + parse + обвязка).
                    val fileTotalNs = System.nanoTime() - fileTotalStartNs
                    minPerFileNs = kotlin.math.min(minPerFileNs, fileTotalNs)
                    maxPerFileNs = kotlin.math.max(maxPerFileNs, fileTotalNs)

                    // Структурированный TEMP_PERF-лог по каждому файлу для анализа распределения задержек.
                    logger.info(
                        "TEMP_PERF|component=app.parse|operation=file_parse|strategy={}|file_name={}|file_size_bytes={}|read_ns={}|read_ms={}|parse_ns={}|parse_ms={}|file_total_ns={}|file_total_ms={}",
                        strategyName,
                        file.name,
                        file.length(),
                        readNs,
                        readNs / 1_000_000.0,
                        parseNs,
                        parseNs / 1_000_000.0,
                        fileTotalNs,
                        fileTotalNs / 1_000_000.0
                    )

                    parsed
                }
            } finally {
                schemaRegistryRepo?.close()
            }

            logger.info("Parsed ${messages.size} messages")

            // Дополнительный анализ первой пары «сырое сообщение → распаршенное сообщение → записи стратегии».
            // Логируем размеры и примерную «плотность» данных по выбранной стратегии.
            try {
                val sampleFile = jsonFiles.firstOrNull()
                val sampleMessage = messages.firstOrNull()
                if (sampleFile != null && sampleMessage != null) {
                    val rawBytes = sampleFile.length()
                    val sampleRecords = strategy.transform(sampleMessage)
                    val recordsJsonBytes = analysisObjectMapper.writeValueAsBytes(sampleRecords).size.toLong()

                    logger.info(
                        "ANALYSIS|component=app.strategy|strategy={}|stage=message_sample|sample_file={}|source_bytes={}|variables_count={}|node_instances_count={}|records_count={}|records_bytes={}",
                        strategyName,
                        sampleFile.name,
                        rawBytes,
                        sampleMessage.variables.size,
                        sampleMessage.nodeInstances.size,
                        sampleRecords.size,
                        recordsJsonBytes
                    )
                } else {
                    logger.info(
                        "ANALYSIS|component=app.strategy|strategy={}|stage=message_sample|status=skipped|reason=no_sample",
                        strategyName
                    )
                }
            } catch (ex: Exception) {
                logger.warn("ANALYSIS|component=app.strategy|strategy=$strategyName|stage=message_sample|status=failed|reason=${ex.message}")
            }

            // Метрика: старт трансформации сообщений в записи Druid.
            val transformStartNs = System.nanoTime()
            val records = messages.flatMap { strategy.transform(it) }
            // Метрика: длительность трансформации сообщений в records.
            val transformNs = System.nanoTime() - transformStartNs
            // Метрика: полная длительность команды parse.
            val parseCommandTotalNs = System.nanoTime() - parseCommandStartNs
            // Метрика: средняя длительность обработки одного файла.
            val avgPerFileMs = if (jsonFiles.isNotEmpty()) (parseCommandTotalNs / 1_000_000.0) / jsonFiles.size else 0.0
            // Метрика: средняя длительность парсинга одного сообщения.
            val avgPerMessageMs = if (messages.isNotEmpty()) (totalParseNs / 1_000_000.0) / messages.size else 0.0
            // Метрика: средняя длительность трансформации одного сообщения.
            val avgTransformPerMessageMs = if (messages.isNotEmpty()) (transformNs / 1_000_000.0) / messages.size else 0.0
            // Метрика: пропускная способность по входным данным (МБ/с) для всей команды parse.
            val throughputMbPerSec = if (parseCommandTotalNs > 0) {
                (totalInputBytes.toDouble() * 1_000_000_000.0 / parseCommandTotalNs) / (1024.0 * 1024.0)
            } else {
                0.0
            }

            // Структурированный TEMP_PERF-лог агрегатов команды parse.
            logger.info(
                "TEMP_PERF|component=app.parse|operation=parse_summary|strategy={}|input_dir={}|files_count={}|messages_count={}|records_count={}|input_bytes_total={}|scan_ns={}|scan_ms={}|read_ns_total={}|read_ms_total={}|parse_ns_total={}|parse_ms_total={}|transform_ns={}|transform_ms={}|min_per_file_ns={}|min_per_file_ms={}|max_per_file_ns={}|max_per_file_ms={}|parse_command_total_ns={}|parse_command_total_ms={}|avg_per_file_ms={}|avg_per_message_ms={}|avg_transform_per_message_ms={}|throughput_mb_s={}|ingest_requested={}",
                strategyName,
                inputDir.absolutePath,
                jsonFiles.size,
                messages.size,
                records.size,
                totalInputBytes,
                scanNs,
                scanNs / 1_000_000.0,
                totalReadNs,
                totalReadNs / 1_000_000.0,
                totalParseNs,
                totalParseNs / 1_000_000.0,
                transformNs,
                transformNs / 1_000_000.0,
                if (jsonFiles.isNotEmpty()) minPerFileNs else 0L,
                if (jsonFiles.isNotEmpty()) minPerFileNs / 1_000_000.0 else 0.0,
                if (jsonFiles.isNotEmpty()) maxPerFileNs else 0L,
                if (jsonFiles.isNotEmpty()) maxPerFileNs / 1_000_000.0 else 0.0,
                parseCommandTotalNs,
                parseCommandTotalNs / 1_000_000.0,
                avgPerFileMs,
                avgPerMessageMs,
                avgTransformPerMessageMs,
                throughputMbPerSec,
                args.contains("--ingest")
            )
            logger.info("Generated ${records.size} records for Druid in ${transformNs / 1_000_000.0}ms (transform)")
            
            // Если указан флаг --ingest, загружаем данные в Druid (с замером времени записи)
            if (args.contains("--ingest")) {
                val druidClient = DruidClient(config.druid)
                druidClient.use { client: DruidClient ->
                    runBlocking {
                        // Метрика: старт времени ingest-этапа внутри команды parse.
                        val ingestStartNs = System.nanoTime()
                        if (strategy.additionalDataSources.isEmpty()) {
                            // Метрика: старт ingest единственного datasource.
                            val dsIngestStartNs = System.nanoTime()
                            client.ingest(strategy.dataSourceName, records)
                            // Метрика: длительность ingest единственного datasource.
                            val dsIngestNs = System.nanoTime() - dsIngestStartNs
                            // Структурированный TEMP_PERF-лог ingest одного datasource.
                            logger.info(
                                "TEMP_PERF|component=app.parse|operation=ingest_datasource|strategy={}|data_source={}|records_count={}|ingest_ns={}|ingest_ms={}",
                                strategyName,
                                strategy.dataSourceName,
                                records.size,
                                dsIngestNs,
                                dsIngestNs / 1_000_000.0
                            )
                            logger.info("Ingested records to Druid datasource: ${strategy.dataSourceName}")
                            verifyExpectedDatasources(client, listOf(strategy.dataSourceName))
                        } else {
                            // Метрика: старт формирования множественных datasource через transformBatch().
                            val splitByDsStartNs = System.nanoTime()
                            val byDataSource = strategy.transformBatch(messages)
                            // Метрика: длительность формирования батчей по datasource.
                            val splitByDsNs = System.nanoTime() - splitByDsStartNs
                            // Структурированный TEMP_PERF-лог подготовки данных по datasource.
                            logger.info(
                                "TEMP_PERF|component=app.parse|operation=prepare_datasource_batches|strategy={}|datasources_count={}|prepare_ns={}|prepare_ms={}",
                                strategyName,
                                byDataSource.size,
                                splitByDsNs,
                                splitByDsNs / 1_000_000.0
                            )
                            byDataSource.forEach { (dataSource: String, dsRecords: List<Map<String, Any?>>) ->
                                // Метрика: старт ingest конкретного datasource.
                                val dsIngestStartNs = System.nanoTime()
                                client.ingest(dataSource, dsRecords)
                                // Метрика: длительность ingest конкретного datasource.
                                val dsIngestNs = System.nanoTime() - dsIngestStartNs
                                // Структурированный TEMP_PERF-лог ingest каждого datasource.
                                logger.info(
                                    "TEMP_PERF|component=app.parse|operation=ingest_datasource|strategy={}|data_source={}|records_count={}|ingest_ns={}|ingest_ms={}",
                                    strategyName,
                                    dataSource,
                                    dsRecords.size,
                                    dsIngestNs,
                                    dsIngestNs / 1_000_000.0
                                )
                                logger.info("Ingested ${dsRecords.size} records to Druid datasource: $dataSource")
                            }

                            val expected = (listOf(strategy.dataSourceName) + strategy.additionalDataSources).distinct()
                            verifyExpectedDatasources(client, expected)

                            // Итоговая метрика: распределение записей по datasource (для Combined/Compcom/EAV).
                            try {
                                val dsCounts = byDataSource.entries
                                    .sortedBy { it.key }
                                    .joinToString(",") { (ds, rs) -> "$ds=${rs.size}" }
                                logger.info(
                                    "TEMP_PERF|component=app.parse|operation=ingest_datasource_distribution|strategy={}|datasource_counts={}|datasources_count={}|records_total={}",
                                    strategyName,
                                    dsCounts,
                                    byDataSource.size,
                                    byDataSource.values.sumOf { it.size }
                                )
                            } catch (ex: Exception) {
                                logger.warn("TEMP_PERF|component=app.parse|operation=ingest_datasource_distribution|strategy=$strategyName|status=failed|reason=${ex.message}")
                            }
                        }
                        // Метрика: полная длительность ingest-этапа команды parse.
                        val ingestNs = System.nanoTime() - ingestStartNs
                        // Структурированный TEMP_PERF-лог итогов ingest-этапа.
                        logger.info(
                            "TEMP_PERF|component=app.parse|operation=ingest_stage_summary|strategy={}|warm_variables_limit_effective={}|ingest_ns={}|ingest_ms={}",
                            strategyName,
                            warmLimit ?: "null",
                            ingestNs,
                            ingestNs / 1_000_000.0
                        )
                        logger.info("Ingest to Druid completed in ${ingestNs / 1_000_000.0}ms (submit tasks)")
                    }
                }
            }
        }
        
        // ==========================================
        // КОМАНДА: query
        // Выполнение SQL-запросов к Druid
        // ==========================================
        "query" -> {
            val queryFile: String = args.getOrElse(1) { "" }
            if (queryFile.isBlank()) {
                logger.error("Usage: query <query-file.sql>")
                return@runBlocking
            }

            // Метрика: старт полного цикла команды query.
            val queryCommandStartNs = System.nanoTime()
            // Метрика: старт чтения SQL-файла.
            val queryReadStartNs = System.nanoTime()
            // Читаем SQL из файла: убираем полнострочные комментарии (-- ...),
            // чтобы парсер Druid не воспринимал идентификаторы из комментариев как колонки
            val queryPath: String = queryFile
            val rawSql = File(queryPath).readText()
            // Метрика: длительность чтения SQL-файла с диска.
            val queryReadNs = System.nanoTime() - queryReadStartNs
            // Метрика: размер SQL-файла (сырой текст) в символах.
            val rawSqlChars = rawSql.length
            // Метрика: старт подготовки SQL (фильтрация комментариев и trim).
            val sqlPrepareStartNs = System.nanoTime()
            val sql = rawSql.lines()
                .filter { line: String -> !line.trim().startsWith("--") }
                .joinToString("\n")
                .trim()
                .replace(Regex(";\\s*$"), "")
            // Метрика: длительность подготовки SQL перед отправкой в Druid.
            val sqlPrepareNs = System.nanoTime() - sqlPrepareStartNs
            // Метрика: размер SQL после очистки комментариев.
            val preparedSqlChars = sql.length
            // Метрика: число строк SQL до и после очистки.
            val rawSqlLines = rawSql.lines().size
            val preparedSqlLines = sql.lines().size
            val druidClient = DruidClient(config.druid)
            druidClient.use { client: DruidClient ->
                runBlocking {
                    // Метрика: старт выполнения SQL-запроса в Druid.
                    val druidExecStartNs = System.nanoTime()
                    val results = client.query(sql)
                    // Метрика: длительность выполнения SQL в Druid (включая сеть + decode на стороне клиента).
                    val druidExecNs = System.nanoTime() - druidExecStartNs
                    // Метрика: старт вывода строк результата в stdout.
                    val printStartNs = System.nanoTime()
                    results.forEach { row: Map<String, Any?> ->
                        println(row)
                    }
                    // Метрика: длительность печати результата в stdout.
                    val printNs = System.nanoTime() - printStartNs
                    // Метрика: полная длительность команды query.
                    val queryCommandTotalNs = System.nanoTime() - queryCommandStartNs
                    // Метрика: средняя длительность на одну строку результата (включая весь цикл команды).
                    val avgPerRowMs = if (results.isNotEmpty()) (queryCommandTotalNs / 1_000_000.0) / results.size else 0.0

                    // Структурированный TEMP_PERF-лог агрегатов команды query.
                    logger.info(
                        "TEMP_PERF|component=app.query|operation=query_summary|query_file={}|raw_sql_chars={}|prepared_sql_chars={}|raw_sql_lines={}|prepared_sql_lines={}|read_ns={}|read_ms={}|prepare_ns={}|prepare_ms={}|druid_exec_ns={}|druid_exec_ms={}|print_ns={}|print_ms={}|rows_count={}|query_command_total_ns={}|query_command_total_ms={}|avg_per_row_ms={}",
                        queryPath,
                        rawSqlChars,
                        preparedSqlChars,
                        rawSqlLines,
                        preparedSqlLines,
                        queryReadNs,
                        queryReadNs / 1_000_000.0,
                        sqlPrepareNs,
                        sqlPrepareNs / 1_000_000.0,
                        druidExecNs,
                        druidExecNs / 1_000_000.0,
                        printNs,
                        printNs / 1_000_000.0,
                        results.size,
                        queryCommandTotalNs,
                        queryCommandTotalNs / 1_000_000.0,
                        avgPerRowMs
                    )
                    logger.info("Query returned ${results.size} rows")
                }
            }
        }

        // ==========================================
        // КОМАНДА: query-suite
        // Прогон всех SQL-файлов для стратегии + метрики (median/p95)
        // ==========================================
        "query-suite" -> {
            val strategyName: String = args.getOrElse(1) { "" }
            if (strategyName.isBlank()) {
                logger.error("Usage: query-suite <strategy> [--repeat N] [--out query-results/<strategy>.txt] [--segment-sizes]")
                return@runBlocking
            }
            val warmLimit = config.parserConfig?.effectiveWarmVariablesLimit()
            val repeat = extractIntFlag(args, "--repeat")?.coerceIn(1, 100) ?: 1
            val outPath = extractStringFlag(args, "--out") ?: "query-results/${strategyName.lowercase()}.txt"
            val includeSegmentSizes = args.contains("--segment-sizes")

            val strategy = createStrategyOrNull(strategyName, config)
            if (strategy == null) {
                logger.error(
                    "Unknown strategy: {}. Use: {}",
                    strategyName,
                    supportedStrategies.joinToString(", ")
                )
                return@runBlocking
            }

            logger.info(
                "ANALYSIS|component=app.strategy|stage=catalog|strategies_count={}|strategies={}",
                supportedStrategies.size,
                supportedStrategies.joinToString(",")
            )
            logger.info(
                "TEMP_PERF|component=app.strategy|operation=selection|strategy={}|strategies_count={}",
                strategyName,
                supportedStrategies.size
            )

            val queryDir = File("query/${strategyName.lowercase()}")
            if (!queryDir.exists()) {
                logger.error("Query directory not found: ${queryDir.absolutePath}")
                return@runBlocking
            }
            val sqlFiles = queryDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "sql" }
                .sortedBy { it.name }
                .toList()
            if (sqlFiles.isEmpty()) {
                logger.warn("No .sql files found in: ${queryDir.absolutePath}")
                return@runBlocking
            }

            File(outPath).parentFile?.mkdirs()
            val reportFile = File(outPath)
            reportFile.writeText("") // reset

            logger.info(
                "ANALYSIS|component=app.query_suite|strategy={}|query_dir={}|files_count={}|repeat={}|out_path={}|warm_variables_limit_effective={}|segment_sizes_requested={}",
                strategyName,
                queryDir.absolutePath,
                sqlFiles.size,
                repeat,
                reportFile.absolutePath,
                warmLimit ?: "null",
                includeSegmentSizes
            )

            val client = DruidClient(config.druid)
            client.use { druid ->
                val allSuccessLatenciesMs = mutableListOf<Double>()
                var totalAttempts = 0
                var okAttempts = 0

                sqlFiles.forEach { file ->
                    val prepared = readAndPrepareSql(file)
                    val fileLatenciesOkMs = mutableListOf<Double>()
                    var fileOk = 0
                    var fileFail = 0

                    repeat(repeat) { attemptIdx ->
                        totalAttempts++
                        val attempt = druid.tryQuery(prepared.sql)
                        val totalMs = attempt.metrics.totalMs
                        val status = attempt.metrics.status
                        val rowsCount = attempt.metrics.rowsCount
                        val success = attempt.success

                        if (success) {
                            okAttempts++
                            fileOk++
                            fileLatenciesOkMs.add(totalMs)
                            allSuccessLatenciesMs.add(totalMs)
                        } else {
                            fileFail++
                        }

                        logger.info(
                            "TEMP_PERF|component=app.query_suite|operation=query_file_attempt|strategy={}|file_name={}|attempt_index={}|repeat={}|status={}|success={}|rows_count={}|total_ms={}|sql_chars={}|raw_sql_chars={}|prepared_sql_chars={}|raw_sql_lines={}|prepared_sql_lines={}",
                            strategyName,
                            file.name,
                            attemptIdx + 1,
                            repeat,
                            status,
                            success,
                            rowsCount,
                            totalMs,
                            attempt.metrics.sqlChars,
                            prepared.rawChars,
                            prepared.preparedChars,
                            prepared.rawLines,
                            prepared.preparedLines
                        )

                        // Строка отчёта: один запуск одного файла.
                        reportFile.appendText(
                            listOf(
                                strategyName.lowercase(),
                                file.name,
                                (attemptIdx + 1).toString(),
                                status.toString(),
                                success.toString(),
                                rowsCount.toString(),
                                "%.3f".format(totalMs),
                                attempt.metrics.sqlChars.toString()
                            ).joinToString("\t") + "\n"
                        )
                    }

                    val fileMedian = percentile(fileLatenciesOkMs, 50.0)
                    val fileP95 = percentile(fileLatenciesOkMs, 95.0)
                    logger.info(
                        "TEMP_PERF|component=app.query_suite|operation=query_file_summary|strategy={}|file_name={}|ok={}|fail={}|median_ms={}|p95_ms={}",
                        strategyName,
                        file.name,
                        fileOk,
                        fileFail,
                        fileMedian ?: -1.0,
                        fileP95 ?: -1.0
                    )
                }

                val overallMedian = percentile(allSuccessLatenciesMs, 50.0)
                val overallP95 = percentile(allSuccessLatenciesMs, 95.0)
                val successRate = if (totalAttempts > 0) okAttempts.toDouble() / totalAttempts else 0.0

                logger.info(
                    "TEMP_PERF|component=app.query_suite|operation=query_suite_summary|strategy={}|files_count={}|repeat={}|attempts_total={}|attempts_ok={}|success_rate={}|median_ms={}|p95_ms={}|out_path={}",
                    strategyName,
                    sqlFiles.size,
                    repeat,
                    totalAttempts,
                    okAttempts,
                    successRate,
                    overallMedian ?: -1.0,
                    overallP95 ?: -1.0,
                    reportFile.absolutePath
                )

                if (includeSegmentSizes) {
                    val datasources = (listOf(strategy.dataSourceName) + strategy.additionalDataSources).distinct()
                    val segRows = fetchSegmentSizesViaSysTable(druid, datasources)
                    logger.info(
                        "ANALYSIS|component=app.query_suite|strategy={}|stage=segment_sizes|datasources={}|rows={}",
                        strategyName,
                        datasources.joinToString(","),
                        segRows.size
                    )
                    segRows.forEach { row ->
                        logger.info("ANALYSIS|component=app.query_suite|strategy={}|stage=segment_size_row|row={}", strategyName, row)
                    }
                }
            }
        }
        
        // ==========================================
        // КОМАНДА: help
        // Вывод справки по использованию
        // ==========================================
        "help" -> {
            println("""
                BPM Message Parser for Apache Druid
                
                Usage:
                  generate [output-dir] [count]   Generate test messages (default: messages, 500)
                  parse <strategy> [input-dir]    Parse messages (hybrid|eav|combined|compcom|default)
                  parse <strategy> --ingest       Parse and ingest to Druid
                  query <query-file.sql>          Execute SQL query on Druid
                  query-suite <strategy>          Run all SQL files in query/<strategy> with metrics
                  help                            Show this help
                
                Examples:
                  java -jar build/libs/bpm-druid-parser-1.0.0.jar generate
                  java -jar build/libs/bpm-druid-parser-1.0.0.jar generate messages 500
                
                Strategies:
                  hybrid   - Flat columns + JSON blobs (single table)
                  eav      - Entity-Attribute-Value (two tables)
                  combined - Tiered approach (hot/warm/cold, with var_blob_json)
                  compcom  - Compact combined (hot/warm, no cold blob in Druid)
                  default  - All message fields as columns (dot-separated names)
            """.trimIndent())
        }
        
        // Неизвестная команда
        else -> {
            logger.error("Unknown command: $command. Use 'help' for usage.")
        }
    }
}

private data class PreparedSql(
    val sql: String,
    val rawChars: Int,
    val preparedChars: Int,
    val rawLines: Int,
    val preparedLines: Int
)

private fun readAndPrepareSql(file: File): PreparedSql {
    val rawSql = file.readText()
    val rawLines = rawSql.lines().size
    val rawChars = rawSql.length
    val sql = rawSql.lines()
        .filter { line: String -> !line.trim().startsWith("--") }
        .joinToString("\n")
        .trim()
        .replace(Regex(";\\s*$"), "")
    return PreparedSql(
        sql = sql,
        rawChars = rawChars,
        preparedChars = sql.length,
        rawLines = rawLines,
        preparedLines = if (sql.isBlank()) 0 else sql.lines().size
    )
}

private suspend fun verifyExpectedDatasources(druid: DruidClient, expected: List<String>) {
    if (expected.isEmpty()) return
    val existing = druid.listDataSources().toSet()
    val missing = expected.filterNot { existing.contains(it) }
    logger.info(
        "ANALYSIS|component=app.ingest|stage=verify_datasources|expected={}|existing_count={}|missing={}",
        expected.joinToString(","),
        existing.size,
        if (missing.isEmpty()) "<none>" else missing.joinToString(",")
    )
    if (missing.isNotEmpty()) {
        throw IllegalStateException("Missing Druid datasource(s) after ingest: ${missing.joinToString(", ")}")
    }
}

private fun extractIntFlag(args: Array<String>, flag: String): Int? {
    val idx = args.indexOf(flag)
    if (idx < 0) return null
    return args.getOrNull(idx + 1)?.toIntOrNull()
}

private fun extractStringFlag(args: Array<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    if (idx < 0) return null
    return args.getOrNull(idx + 1)
}

private fun percentile(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = kotlin.math.floor(rank).toInt()
    val hi = kotlin.math.ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val w = rank - lo
    return sorted[lo] * (1.0 - w) + sorted[hi] * w
}

private suspend fun fetchSegmentSizesViaSysTable(druid: DruidClient, datasources: List<String>): List<Map<String, Any?>> {
    if (datasources.isEmpty()) return emptyList()
    val inList = datasources.joinToString(",") { "'$it'" }
    val sql = """
        SELECT datasource, SUM(size) AS bytes, COUNT(*) AS segments
        FROM sys.segments
        WHERE datasource IN ($inList)
        GROUP BY 1
        ORDER BY bytes DESC
    """.trimIndent()
    // Используем tryQuery, чтобы в отчётах не падать, если sys.* не доступен в конкретной сборке Druid.
    val attempt = druid.tryQuery(sql)
    return if (attempt.success) attempt.rows ?: emptyList() else emptyList()
}
