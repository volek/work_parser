package ru.sber.parser

import kotlinx.coroutines.runBlocking
import ru.sber.parser.config.AppConfig
import ru.sber.parser.druid.DruidClient
import ru.sber.parser.generator.MessageGenerator
import ru.sber.parser.parser.MessageParser
import ru.sber.parser.parser.strategy.CombinedStrategy
import ru.sber.parser.parser.strategy.EavStrategy
import ru.sber.parser.parser.strategy.HybridStrategy
import ru.sber.parser.parser.strategy.ParseStrategy
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Логгер для главного модуля приложения.
 */
private val logger = LoggerFactory.getLogger("Application")

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
            logger.info("Generating test messages...")
            val generator = MessageGenerator()
            // Директория для сохранения сообщений (по умолчанию "messages")
            val outputDir = File(args.getOrElse(1) { "messages" })
            generator.generateAll(outputDir)
            logger.info("Generated messages saved to: ${outputDir.absolutePath}")
        }
        
        // ==========================================
        // КОМАНДА: parse
        // Парсинг сообщений с выбранной стратегией
        // ==========================================
        "parse" -> {
            // Название стратегии: hybrid, eav или combined
            val strategyName = args.getOrElse(1) { "hybrid" }
            // Директория с входными JSON-файлами (пропускаем флаги, начинающиеся с --)
            val positionalArgs = args.filter { !it.startsWith("--") }
            val inputDir = File(positionalArgs.getOrElse(2) { "messages" })
            
            // Выбор стратегии трансформации данных
            val strategy: ParseStrategy = when (strategyName.lowercase()) {
                "hybrid" -> HybridStrategy(config.fieldClassification)   // Одна таблица с JSON-блобами
                "eav" -> EavStrategy()                                    // Две таблицы: события + переменные
                "combined" -> CombinedStrategy(config.fieldClassification) // Горячие колонки + индекс
                else -> {
                    logger.error("Unknown strategy: $strategyName. Use: hybrid, eav, combined")
                    return@runBlocking
                }
            }
            
            logger.info("Parsing messages with strategy: $strategyName")
            
            // Парсим все JSON-файлы из входной директории
            val messages = inputDir.listFiles { f -> f.extension == "json" }?.map { file ->
                parser.parse(file.readText())
            } ?: emptyList()
            
            logger.info("Parsed ${messages.size} messages")
            
            // Трансформируем сообщения в записи для Druid
            val records = messages.flatMap { strategy.transform(it) }
            logger.info("Generated ${records.size} records for Druid")
            
            // Если указан флаг --ingest, загружаем данные в Druid
            if (args.contains("--ingest")) {
                val druidClient = DruidClient(config.druid)
                druidClient.use { client ->
                    client.ingest(strategy.dataSourceName, records)
                    logger.info("Ingested records to Druid datasource: ${strategy.dataSourceName}")
                }
            }
        }
        
        // ==========================================
        // КОМАНДА: query
        // Выполнение SQL-запросов к Druid
        // ==========================================
        "query" -> {
            val queryFile = args.getOrElse(1) { "" }
            if (queryFile.isBlank()) {
                logger.error("Usage: query <query-file.sql>")
                return@runBlocking
            }
            
            // Читаем SQL из файла и выполняем запрос
            val sql = File(queryFile).readText()
            val druidClient = DruidClient(config.druid)
            druidClient.use { client ->
                val results = client.query(sql)
                results.forEach { row ->
                    println(row)
                }
                logger.info("Query returned ${results.size} rows")
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
                  generate [output-dir]           Generate test messages
                  parse <strategy> [input-dir]    Parse messages (hybrid|eav|combined)
                  parse <strategy> --ingest       Parse and ingest to Druid
                  query <query-file.sql>          Execute SQL query on Druid
                  help                            Show this help
                
                Strategies:
                  hybrid   - Flat columns + JSON blobs (single table)
                  eav      - Entity-Attribute-Value (two tables)
                  combined - Tiered approach (hot/warm/cold)
            """.trimIndent())
        }
        
        // Неизвестная команда
        else -> {
            logger.error("Unknown command: $command. Use 'help' for usage.")
        }
    }
}
