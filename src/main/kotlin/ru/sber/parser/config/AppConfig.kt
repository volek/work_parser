package ru.sber.parser.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Главный класс конфигурации приложения.
 * 
 * Объединяет все настройки приложения в одном месте:
 * - Параметры подключения к Apache Druid
 * - Классификация полей для стратегий парсинга
 * 
 * Конфигурация загружается с приоритетом:
 * 1. Переменные окружения (высший приоритет)
 * 2. Файл config.yaml
 * 3. Значения по умолчанию
 * 
 * @property druid Настройки подключения к Apache Druid
 * @property fieldClassification Классификация полей по уровням (hot/warm/cold)
 * @property parserConfig Опции парсера (лимит warm-переменных для combined/compcom)
 */
data class AppConfig(
    val druid: DruidConfig = DruidConfig.fromEnvironment(),
    val fieldClassification: FieldClassification = FieldClassification.default(),
    val parserConfig: ParserConfig? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AppConfig::class.java)
        
        /**
         * Маппер для чтения YAML-конфигурации.
         * Настроен для работы с Kotlin data classes и Java Time API.
         */
        private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        
        /**
         * Загружает конфигурацию приложения.
         *
         * Порядок приоритета источников:
         * 1. Переменные окружения (DRUID_BROKER_URL, DRUID_BATCH_SIZE и т.д.)
         * 2. Значения из YAML-файла конфигурации (если файл существует)
         *    - путь берётся из PARSER_CONFIG_PATH, если переменная задана
         *    - иначе используется параметр configPath (по умолчанию config.yaml)
         * 3. Значения по умолчанию (localhost:8082, batch=1000 и т.д.)
         *
         * @param configPath Путь к YAML-файлу конфигурации по умолчанию
         * @return Загруженная конфигурация приложения
         */
        fun load(configPath: String = "config.yaml"): AppConfig {
            val configPathFromEnv = System.getenv("PARSER_CONFIG_PATH")?.takeIf { it.isNotBlank() }
            val effectiveConfigPath = configPathFromEnv ?: configPath
            val configFile = File(effectiveConfigPath)
            logger.info(
                "Config resolution: PARSER_CONFIG_PATH={}, effective path={}",
                configPathFromEnv ?: "<not set>",
                configFile.absolutePath
            )
            
            // Пытаемся загрузить конфигурацию из файла
            val fileConfig: FileConfig? = if (configFile.exists()) {
                try {
                    logger.info("Loading configuration from: ${configFile.absolutePath}")
                    yamlMapper.readValue(configFile)
                } catch (e: Exception) {
                    logger.warn("Failed to load config file, using defaults: ${e.message}")
                    null
                }
            } else {
                logger.info("Config file not found at $effectiveConfigPath, using environment variables and defaults")
                null
            }
            
            // Собираем итоговую конфигурацию с учётом приоритетов (ENV > file)
            val warmFromEnv = System.getenv("PARSER_WARM_VARIABLES_LIMIT")?.toIntOrNull()
            val parserConfig = when {
                warmFromEnv != null -> ParserConfig(warmVariablesLimit = warmFromEnv)
                else -> fileConfig?.parserConfig
            }
            return AppConfig(
                druid = DruidConfig.fromEnvironmentWithFallback(fileConfig?.druid),
                fieldClassification = fileConfig?.fieldClassification ?: FieldClassification.default(),
                parserConfig = parserConfig
            )
        }
    }
}

/**
 * Промежуточный класс для парсинга YAML файла
 */
private data class FileConfig(
    val druid: DruidFileConfig? = null,
    val fieldClassification: FieldClassification? = null,
    val parser: ParserFileConfig? = null
) {
    val parserConfig: ParserConfig?
        get() = parser?.let { ParserConfig(warmVariablesLimit = it.warmVariablesLimit) }
}

/**
 * Настройки парсера (YAML-секция parser).
 */
private data class ParserFileConfig(
    val warmVariablesLimit: Int? = null
)

/**
 * Конфигурация парсера для стратегий combined/compcom.
 *
 * @property warmVariablesLimit Максимум записей process_variables_indexed на одно сообщение.
 *                              Допустимый диапазон: 10..1010 с шагом 100 (10, 110, 210, ..., 1010).
 *                              null = без ограничения.
 */
data class ParserConfig(
    val warmVariablesLimit: Int? = null
) {
    /**
     * Возвращает лимит, приведённый к допустимому диапазону, или null.
     */
    fun effectiveWarmVariablesLimit(): Int? {
        val v = warmVariablesLimit ?: return null
        if (v < 10) return null
        if (v > 1010) return 1010
        return v
    }
}

internal data class DruidFileConfig(
    val brokerUrl: String? = null,
    val coordinatorUrl: String? = null,
    val overlordUrl: String? = null,
    val routerUrl: String? = null,
    val connectTimeout: Long? = null,
    val readTimeout: Long? = null,
    val batchSize: Int? = null,
    /**
     * Максимальный размер inline-NDJSON payload в ingestion spec (в байтах).
     * При превышении — батч будет автоматически дробиться на более мелкие,
     * чтобы снизить вероятность сетевых обрывов/лимитов (e.g. Broken pipe).
     */
    val maxInlineBytes: Int? = null
)

/**
 * Конфигурация подключения к Apache Druid.
 * 
 * Поддерживает несколько режимов подключения:
 * - Standalone Druid на localhost
 * - Druid в отдельном Docker контейнере
 * - Удалённый Druid кластер
 * 
 * Приоритет значений: ENV > config.yaml > defaults
 */
data class DruidConfig(
    val brokerUrl: String = "http://localhost:8082",
    val coordinatorUrl: String = "http://localhost:8081",
    val overlordUrl: String = "http://localhost:8081",
    val routerUrl: String = "http://localhost:8888",
    val connectTimeout: Long = 30000,
    val readTimeout: Long = 60000,
    val batchSize: Int = 1000,
    /**
     * Максимальный размер inline-NDJSON payload в ingestion spec (в байтах).
     * Нужен для стабилизации submit в Overlord (избежать "Broken pipe" при больших запросах).
     */
    val maxInlineBytes: Int = 4_000_000
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DruidConfig::class.java)
        
        /**
         * Создаёт конфигурацию только из переменных окружения
         */
        fun fromEnvironment(): DruidConfig = fromEnvironmentWithFallback(null)
        
        /**
         * Создаёт конфигурацию с приоритетом: ENV > fileConfig > defaults
         */
        internal fun fromEnvironmentWithFallback(fileConfig: DruidFileConfig?): DruidConfig {
            val config = DruidConfig(
                brokerUrl = System.getenv("DRUID_BROKER_URL") 
                    ?: fileConfig?.brokerUrl 
                    ?: "http://localhost:8082",
                coordinatorUrl = System.getenv("DRUID_COORDINATOR_URL") 
                    ?: fileConfig?.coordinatorUrl 
                    ?: "http://localhost:8081",
                overlordUrl = System.getenv("DRUID_OVERLORD_URL") 
                    ?: fileConfig?.overlordUrl 
                    ?: "http://localhost:8081",
                routerUrl = System.getenv("DRUID_ROUTER_URL") 
                    ?: fileConfig?.routerUrl 
                    ?: "http://localhost:8888",
                connectTimeout = System.getenv("DRUID_CONNECT_TIMEOUT")?.toLongOrNull() 
                    ?: fileConfig?.connectTimeout 
                    ?: 30000,
                readTimeout = System.getenv("DRUID_READ_TIMEOUT")?.toLongOrNull() 
                    ?: fileConfig?.readTimeout 
                    ?: 60000,
                batchSize = System.getenv("DRUID_BATCH_SIZE")?.toIntOrNull() 
                    ?: fileConfig?.batchSize 
                    ?: 1000,
                maxInlineBytes = System.getenv("DRUID_MAX_INLINE_BYTES")?.toIntOrNull()
                    ?: fileConfig?.maxInlineBytes
                    ?: 4_000_000
            )
            
            logger.info("Druid configuration loaded:")
            logger.info("  Router URL: ${config.routerUrl}")
            logger.info("  Broker URL: ${config.brokerUrl}")
            logger.info("  Coordinator URL: ${config.coordinatorUrl}")
            logger.info("  Overlord URL: ${config.overlordUrl}")
            logger.info("  Ingest batch size: ${config.batchSize}")
            logger.info("  Ingest max inline bytes: ${config.maxInlineBytes}")
            
            return config
        }
        
        /**
         * Создаёт конфигурацию для standalone Druid на localhost
         */
        fun forLocalStandalone(port: Int = 8888): DruidConfig {
            return DruidConfig(
                brokerUrl = "http://localhost:8082",
                coordinatorUrl = "http://localhost:8081",
                overlordUrl = "http://localhost:8081",
                routerUrl = "http://localhost:$port"
            )
        }
        
        /**
         * Создаёт конфигурацию для Druid в Docker (через host.docker.internal)
         */
        fun forDockerHost(): DruidConfig {
            return DruidConfig(
                brokerUrl = "http://host.docker.internal:8082",
                coordinatorUrl = "http://host.docker.internal:8081",
                overlordUrl = "http://host.docker.internal:8081",
                routerUrl = "http://host.docker.internal:8888"
            )
        }
        
        /**
         * Создаёт конфигурацию для Druid кластера в Docker Compose
         */
        fun forDockerCompose(): DruidConfig {
            return DruidConfig(
                brokerUrl = "http://druid-broker:8082",
                coordinatorUrl = "http://druid-coordinator:8081",
                overlordUrl = "http://druid-overlord:8090",
                routerUrl = "http://druid-router:8888"
            )
        }
    }
    
    /**
     * Проверяет доступность Druid по router URL
     */
    fun getHealthCheckUrl(): String = "$routerUrl/status/health"
}
