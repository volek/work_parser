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
    val brokerUrls: List<String>? = null,
    val coordinatorUrl: String? = null,
    val coordinatorUrls: List<String>? = null,
    val overlordUrl: String? = null,
    val overlordUrls: List<String>? = null,
    val routerUrl: String? = null,
    val routerUrls: List<String>? = null,
    val username: String? = null,
    val password: String? = null,
    val connectTimeout: Long? = null,
    val readTimeout: Long? = null,
    val batchSize: Int? = null,
    /**
     * Полный путь к truststore (JKS/PKCS12) для TLS-подключений к Druid.
     */
    val trustStorePath: String? = null,
    /**
     * Пароль truststore.
     */
    val trustStorePassword: String? = null,
    /**
     * Тип truststore: JKS (по умолчанию) или PKCS12.
     */
    val trustStoreType: String? = null,
    /**
     * Опасный dev-only режим: отключает проверку TLS-сертификата.
     */
    val insecureSkipTlsVerify: Boolean? = null,
    /**
     * Максимальный размер inline-NDJSON payload в ingestion spec (в байтах).
     * При превышении — батч будет автоматически дробиться на более мелкие,
     * чтобы снизить вероятность сетевых обрывов/лимитов (e.g. Broken pipe).
     */
    val maxInlineBytes: Int? = null
    ,
    /**
     * Включить ожидание финального статуса ingestion task после submit.
     */
    val awaitIngestionTasks: Boolean? = null,
    /**
     * Таймаут ожидания всех ingestion task одного datasource (мс).
     */
    val ingestionTaskTimeoutMs: Long? = null,
    /**
     * Интервал опроса статусов ingestion task (мс).
     */
    val ingestionTaskPollIntervalMs: Long? = null,
    /**
     * Количество повторных попыток для batch при ошибке назначения task
     * (Failed to assign this task).
     */
    val ingestionAssignRetryCount: Int? = null,
    /**
     * Задержка между retry при ошибке назначения task (мс).
     */
    val ingestionAssignRetryDelayMs: Long? = null
)

/**
 * Конфигурация подключения к Apache Druid.
 * 
 * Поддерживает подключение к локальному или удалённому Druid по URL.
 * 
 * Приоритет значений: ENV > config.yaml > defaults
 */
data class DruidConfig(
    val brokerUrl: String = "http://localhost:8082",
    val brokerUrls: List<String> = listOf("http://localhost:8082"),
    val coordinatorUrl: String = "http://localhost:8081",
    val coordinatorUrls: List<String> = listOf("http://localhost:8081"),
    val overlordUrl: String = "http://localhost:8081",
    val overlordUrls: List<String> = listOf("http://localhost:8081"),
    val routerUrl: String = "http://localhost:8888",
    val routerUrls: List<String> = listOf("http://localhost:8888"),
    val username: String? = null,
    val password: String? = null,
    val connectTimeout: Long = 30000,
    val readTimeout: Long = 60000,
    val batchSize: Int = 1000,
    /**
     * Полный путь к truststore (JKS/PKCS12) для TLS-подключений к Druid.
     */
    val trustStorePath: String? = null,
    /**
     * Пароль truststore.
     */
    val trustStorePassword: String? = null,
    /**
     * Тип truststore: JKS (по умолчанию) или PKCS12.
     */
    val trustStoreType: String = "JKS",
    /**
     * Опасный dev-only режим: отключает проверку TLS-сертификата.
     */
    val insecureSkipTlsVerify: Boolean = false,
    /**
     * Максимальный размер inline-NDJSON payload в ingestion spec (в байтах).
     * Нужен для стабилизации submit в Overlord (избежать "Broken pipe" при больших запросах).
     */
    val maxInlineBytes: Int = 4_000_000,
    /**
     * Ожидать финальный статус ingestion task после submit.
     */
    val awaitIngestionTasks: Boolean = true,
    /**
     * Таймаут ожидания всех ingestion task одного datasource (мс).
     */
    val ingestionTaskTimeoutMs: Long = 1_800_000,
    /**
     * Интервал опроса статусов ingestion task (мс).
     */
    val ingestionTaskPollIntervalMs: Long = 2_000,
    /**
     * Количество повторных попыток для batch при ошибке назначения task
     * (Failed to assign this task).
     */
    val ingestionAssignRetryCount: Int = 3,
    /**
     * Задержка между retry при ошибке назначения task (мс).
     */
    val ingestionAssignRetryDelayMs: Long = 2_000
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DruidConfig::class.java)

        private fun normalizeUrl(raw: String?, fallback: String): String {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return fallback
            return if (value.contains("://")) value else "http://$value"
        }

        private fun normalizeUrls(raw: List<String>?): List<String> {
            return raw.orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { value -> if (value.contains("://")) value else "http://$value" }
                .distinct()
        }

        private fun parseUrlListFromEnv(envName: String): List<String> {
            val raw = System.getenv(envName)?.trim().orEmpty()
            if (raw.isBlank()) return emptyList()
            return raw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        private fun resolveUrlSet(
            singleFromEnv: String?,
            listFromEnv: List<String>,
            singleFromFile: String?,
            listFromFile: List<String>?,
            fallback: String
        ): Pair<String, List<String>> {
            val envSingleNormalized = singleFromEnv?.trim().takeUnless { it.isNullOrBlank() }
                ?.let { normalizeUrl(it, fallback) }
            if (envSingleNormalized != null) {
                return envSingleNormalized to listOf(envSingleNormalized)
            }

            val envListNormalized = normalizeUrls(listFromEnv)
            if (envListNormalized.isNotEmpty()) {
                return envListNormalized.first() to envListNormalized
            }

            val fileListNormalized = normalizeUrls(listFromFile)
            if (fileListNormalized.isNotEmpty()) {
                return fileListNormalized.first() to fileListNormalized
            }

            val fileSingleNormalized = singleFromFile?.trim().takeUnless { it.isNullOrBlank() }
                ?.let { normalizeUrl(it, fallback) }
            if (fileSingleNormalized != null) {
                return fileSingleNormalized to listOf(fileSingleNormalized)
            }

            val normalizedFallback = normalizeUrl(null, fallback)
            return normalizedFallback to listOf(normalizedFallback)
        }
        
        /**
         * Создаёт конфигурацию только из переменных окружения
         */
        fun fromEnvironment(): DruidConfig = fromEnvironmentWithFallback(null)
        
        /**
         * Создаёт конфигурацию с приоритетом: ENV > fileConfig > defaults
         */
        internal fun fromEnvironmentWithFallback(fileConfig: DruidFileConfig?): DruidConfig {
            val (brokerUrl, brokerUrls) = resolveUrlSet(
                singleFromEnv = System.getenv("DRUID_BROKER_URL"),
                listFromEnv = parseUrlListFromEnv("DRUID_BROKER_URLS"),
                singleFromFile = fileConfig?.brokerUrl,
                listFromFile = fileConfig?.brokerUrls,
                fallback = "http://localhost:8082"
            )
            val (coordinatorUrl, coordinatorUrls) = resolveUrlSet(
                singleFromEnv = System.getenv("DRUID_COORDINATOR_URL"),
                listFromEnv = parseUrlListFromEnv("DRUID_COORDINATOR_URLS"),
                singleFromFile = fileConfig?.coordinatorUrl,
                listFromFile = fileConfig?.coordinatorUrls,
                fallback = "http://localhost:8081"
            )
            val (overlordUrl, overlordUrls) = resolveUrlSet(
                singleFromEnv = System.getenv("DRUID_OVERLORD_URL"),
                listFromEnv = parseUrlListFromEnv("DRUID_OVERLORD_URLS"),
                singleFromFile = fileConfig?.overlordUrl,
                listFromFile = fileConfig?.overlordUrls,
                fallback = "http://localhost:8081"
            )
            val (routerUrl, routerUrls) = resolveUrlSet(
                singleFromEnv = System.getenv("DRUID_ROUTER_URL"),
                listFromEnv = parseUrlListFromEnv("DRUID_ROUTER_URLS"),
                singleFromFile = fileConfig?.routerUrl,
                listFromFile = fileConfig?.routerUrls,
                fallback = "http://localhost:8888"
            )

            val config = DruidConfig(
                brokerUrl = brokerUrl,
                brokerUrls = brokerUrls,
                coordinatorUrl = coordinatorUrl,
                coordinatorUrls = coordinatorUrls,
                overlordUrl = overlordUrl,
                overlordUrls = overlordUrls,
                routerUrl = routerUrl,
                routerUrls = routerUrls,
                username = System.getenv("DRUID_USERNAME") ?: fileConfig?.username,
                password = System.getenv("DRUID_PASSWORD") ?: fileConfig?.password,
                connectTimeout = System.getenv("DRUID_CONNECT_TIMEOUT")?.toLongOrNull() 
                    ?: fileConfig?.connectTimeout 
                    ?: 30000,
                readTimeout = System.getenv("DRUID_READ_TIMEOUT")?.toLongOrNull() 
                    ?: fileConfig?.readTimeout 
                    ?: 60000,
                batchSize = System.getenv("DRUID_BATCH_SIZE")?.toIntOrNull() 
                    ?: fileConfig?.batchSize 
                    ?: 1000,
                trustStorePath = System.getenv("DRUID_TRUST_STORE_PATH")
                    ?: fileConfig?.trustStorePath,
                trustStorePassword = System.getenv("DRUID_TRUST_STORE_PASSWORD")
                    ?: fileConfig?.trustStorePassword,
                trustStoreType = System.getenv("DRUID_TRUST_STORE_TYPE")
                    ?: fileConfig?.trustStoreType
                    ?: "JKS",
                insecureSkipTlsVerify = System.getenv("DRUID_INSECURE_SKIP_TLS_VERIFY")
                    ?.trim()
                    ?.lowercase()
                    ?.let { it == "true" || it == "1" || it == "yes" }
                    ?: fileConfig?.insecureSkipTlsVerify
                    ?: false,
                maxInlineBytes = System.getenv("DRUID_MAX_INLINE_BYTES")?.toIntOrNull()
                    ?: fileConfig?.maxInlineBytes
                    ?: 4_000_000,
                awaitIngestionTasks = System.getenv("DRUID_AWAIT_INGESTION_TASKS")
                    ?.trim()
                    ?.lowercase()
                    ?.let { it == "true" || it == "1" || it == "yes" }
                    ?: fileConfig?.awaitIngestionTasks
                    ?: true,
                ingestionTaskTimeoutMs = System.getenv("DRUID_INGESTION_TASK_TIMEOUT_MS")?.toLongOrNull()
                    ?: fileConfig?.ingestionTaskTimeoutMs
                    ?: 1_800_000,
                ingestionTaskPollIntervalMs = System.getenv("DRUID_INGESTION_TASK_POLL_INTERVAL_MS")?.toLongOrNull()
                    ?: fileConfig?.ingestionTaskPollIntervalMs
                    ?: 2_000,
                ingestionAssignRetryCount = System.getenv("DRUID_INGESTION_ASSIGN_RETRY_COUNT")?.toIntOrNull()
                    ?: fileConfig?.ingestionAssignRetryCount
                    ?: 3,
                ingestionAssignRetryDelayMs = System.getenv("DRUID_INGESTION_ASSIGN_RETRY_DELAY_MS")?.toLongOrNull()
                    ?: fileConfig?.ingestionAssignRetryDelayMs
                    ?: 2_000
            )
            
            logger.info("Druid configuration loaded:")
            logger.info("  Router URL: ${config.routerUrl}")
            logger.info("  Router URLs: ${config.routerUrls}")
            logger.info("  Broker URL: ${config.brokerUrl}")
            logger.info("  Broker URLs: ${config.brokerUrls}")
            logger.info("  Coordinator URL: ${config.coordinatorUrl}")
            logger.info("  Coordinator URLs: ${config.coordinatorUrls}")
            logger.info("  Overlord URL: ${config.overlordUrl}")
            logger.info("  Overlord URLs: ${config.overlordUrls}")
            logger.info("  Auth enabled: ${!config.username.isNullOrBlank()}")
            logger.info("  Ingest batch size: ${config.batchSize}")
            logger.info("  TLS trustStore configured: ${!config.trustStorePath.isNullOrBlank()}")
            logger.info("  TLS insecureSkipTlsVerify: ${config.insecureSkipTlsVerify}")
            logger.info("  Ingest max inline bytes: ${config.maxInlineBytes}")
            logger.info("  Await ingestion tasks: ${config.awaitIngestionTasks}")
            logger.info("  Ingestion task timeout ms: ${config.ingestionTaskTimeoutMs}")
            logger.info("  Ingestion task poll interval ms: ${config.ingestionTaskPollIntervalMs}")
            logger.info("  Ingestion assign retry count: ${config.ingestionAssignRetryCount}")
            logger.info("  Ingestion assign retry delay ms: ${config.ingestionAssignRetryDelayMs}")
            
            return config
        }
        
    }
    
    /**
     * Проверяет доступность Druid по router URL
     */
    fun getHealthCheckUrl(): String = "$routerUrl/status/health"
}
