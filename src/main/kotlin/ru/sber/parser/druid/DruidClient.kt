package ru.sber.parser.druid

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ru.sber.parser.config.DruidConfig
import java.io.Closeable
import java.io.FileInputStream
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP-клиент для взаимодействия с Apache Druid.
 * 
 * Предоставляет высокоуровневый API для:
 * - Выполнения SQL-запросов через Router
 * - Загрузки данных через Overlord (ingestion)
 * - Управления datasource через Coordinator
 * - Мониторинга задач индексации
 * 
 * Архитектура подключения:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ DruidClient                                                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ Router (8888)       │ SQL-запросы, маршрутизация                   │
 * │ Overlord (8081)    │ Ingestion tasks, статус задач                │
 * │ Coordinator (8081)  │ Управление datasource, сегменты              │
 * │ Broker (8082)       │ Native queries (не используется напрямую)    │
 * └─────────────────────────────────────────────────────────────────────┘
 * 
 * Особенности реализации:
 * - Ktor HTTP Client с CIO-движком для асинхронных запросов
 * - Jackson для сериализации JSON
 * - Автоматическая разбивка на batch при ingestion
 * - Логирование всех HTTP-операций
 * 
 * Использование:
 * ```kotlin
 * val client = DruidClient(config)
 * 
 * // SQL-запрос
 * val results = client.query("SELECT * FROM my_table LIMIT 10")
 * 
 * // Загрузка данных
 * client.ingest("my_datasource", records)
 * 
 * // Не забыть закрыть
 * client.close()
 * ```
 * 
 * @property config Конфигурация подключения к Druid
 * 
 * @see DruidConfig параметры подключения
 * @see IngestionSpec спецификация для создания datasource
 * @see TaskStatus статус задачи индексации
 */
class DruidClient(private val config: DruidConfig) : Closeable {
    
    private val logger = LoggerFactory.getLogger(DruidClient::class.java)
    init {
        configureJvmTrustStore(config)
    }
    private val tlsTrustManager: X509TrustManager? = createTrustManager(config)
    private val routerEndpoints = EndpointPool("router", config.routerUrls)
    private val overlordEndpoints = EndpointPool("overlord", config.overlordUrls)
    private val coordinatorEndpoints = EndpointPool("coordinator", config.coordinatorUrls)
    
    /** Jackson ObjectMapper для сериализации/десериализации JSON */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())

    private val basicAuthHeader: String? = config.username
        ?.takeIf { it.isNotBlank() }
        ?.let { user ->
            val credentials = "$user:${config.password.orEmpty()}"
            "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
        }

    private companion object {
        // ZK hard-limit is 524288 bytes; keep a small buffer for metadata/serialization differences.
        private const val MAX_SAFE_TASK_SPEC_BYTES = 500_000
    }
    
    /**
     * Ktor HTTP-клиент с настройками для Druid.
     * 
     * Конфигурация:
     * - CIO-движок: неблокирующий I/O на корутинах
     * - ContentNegotiation: автоматическая сериализация Jackson
     * - Logging: логирование HTTP-запросов (INFO уровень)
     * - HttpTimeout: таймауты из DruidConfig
     * - defaultRequest: Content-Type: application/json
     */
    private val httpClient = HttpClient(CIO) {
        engine {
            https {
                tlsTrustManager?.let { this.trustManager = it }
            }
        }
        install(ContentNegotiation) {
            jackson {
                registerModule(JavaTimeModule())
            }
        }
        install(HttpRequestRetry) {
            maxRetries = 4
            retryIf { _, response ->
                // transient statuses; for ingestion submit Overlord can return 5xx under load
                response.status.value == 429 || response.status.value in 500..599
            }
            retryOnExceptionIf { _, cause ->
                // Broken pipe / connection reset, etc.
                cause is java.io.IOException
            }
            exponentialDelay()
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.readTimeout
            connectTimeoutMillis = config.connectTimeout
        }
        val user = config.username
        if (!user.isNullOrBlank()) {
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(
                            username = user,
                            password = config.password.orEmpty()
                        )
                    }
                    sendWithoutRequest { true }
                }
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            basicAuthHeader?.let { header(HttpHeaders.Authorization, it) }
        }
    }
    
    /**
     * Выполняет SQL-запрос к Druid.
     * 
     * Отправляет запрос на Router endpoint /druid/v2/sql.
     * Druid SQL поддерживает SELECT, EXPLAIN, DESCRIBE.
     * 
     * Примеры запросов:
     * - "SELECT * FROM bpm_hybrid WHERE var_caseId = '123'"
     * - "SELECT COUNT(*) FROM bpm_eav_events GROUP BY process_name"
     * - "DESCRIBE bpm_combined_main"
     * 
     * @param sql SQL-запрос
     * @return Список записей как Map<String, Any?>
     * @throws DruidException при ошибке выполнения
     */
    suspend fun query(sql: String): List<Map<String, Any?>> {
        logger.debug("Executing query: {}", sql.take(200))

        val attempt = executeSqlAttempt(sql)
        if (!attempt.success) {
            val msg = "Query failed: HTTP ${attempt.metrics.status}"
            logger.error(msg + (attempt.errorBodyPreview?.let { " ($it)" } ?: ""))
            throw DruidException(msg, attempt.errorBody)
        }
        return attempt.rows ?: emptyList()
    }

    /**
     * Выполняет SQL-запрос и возвращает результат вместе с метриками.
     *
     * В отличие от [query], не бросает исключение на неуспешный HTTP-статус —
     * удобно для бенчмарков/пакетного прогона запросов (раздел 7 отчёта).
     */
    suspend fun tryQuery(sql: String): SqlQueryAttempt = executeSqlAttempt(sql)

    data class SqlQueryMetrics(
        val status: Int,
        val sqlChars: Int,
        val rowsCount: Int,
        val httpRoundTripNs: Long,
        val bodyReadNs: Long,
        val jsonDecodeNs: Long,
        val totalNs: Long,
        val responseBodyChars: Int?,
        val errorBodyChars: Int?
    ) {
        val httpRoundTripMs: Double get() = httpRoundTripNs / 1_000_000.0
        val bodyReadMs: Double get() = bodyReadNs / 1_000_000.0
        val jsonDecodeMs: Double get() = jsonDecodeNs / 1_000_000.0
        val totalMs: Double get() = totalNs / 1_000_000.0
        val avgPerRowMs: Double get() = if (rowsCount > 0) totalMs / rowsCount else 0.0
    }

    data class SqlQueryAttempt(
        val success: Boolean,
        val rows: List<Map<String, Any?>>?,
        val metrics: SqlQueryMetrics,
        val errorBody: String? = null,
        val errorBodyPreview: String? = null
    )

    private suspend fun executeSqlAttempt(sql: String): SqlQueryAttempt {
        // Метрика: размер SQL-запроса в символах (оценка сложности и нагрузки на парсер SQL в Druid).
        val sqlChars = sql.length
        val totalStartNs = System.nanoTime()

        val httpStartNs = System.nanoTime()
        val response: HttpResponse = requestWithFailover(
            pool = routerEndpoints,
            path = "/druid/v2/sql"
        ) { baseUrl ->
            httpClient.post("$baseUrl/druid/v2/sql") {
                setBody(mapOf("query" to sql))
            }
        }
        val httpRoundTripNs = System.nanoTime() - httpStartNs

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            val totalNs = System.nanoTime() - totalStartNs
            val metrics = SqlQueryMetrics(
                status = response.status.value,
                sqlChars = sqlChars,
                rowsCount = 0,
                httpRoundTripNs = httpRoundTripNs,
                bodyReadNs = 0L,
                jsonDecodeNs = 0L,
                totalNs = totalNs,
                responseBodyChars = null,
                errorBodyChars = errorBody.length
            )
            logger.info(
                "TEMP_PERF|component=druid.query|operation=sql_request_failed|sql_chars={}|status={}|http_round_trip_ns={}|http_round_trip_ms={}|total_ns={}|total_ms={}|error_body_chars={}",
                metrics.sqlChars,
                metrics.status,
                metrics.httpRoundTripNs,
                metrics.httpRoundTripMs,
                metrics.totalNs,
                metrics.totalMs,
                metrics.errorBodyChars ?: 0
            )
            return SqlQueryAttempt(
                success = false,
                rows = null,
                metrics = metrics,
                errorBody = errorBody,
                errorBodyPreview = errorBody.take(300)
            )
        }

        val bodyReadStartNs = System.nanoTime()
        val body = response.bodyAsText()
        val bodyReadNs = System.nanoTime() - bodyReadStartNs
        val bodyChars = body.length

        val decodeStartNs = System.nanoTime()
        val rows: List<Map<String, Any?>> = objectMapper.readValue(body)
        val decodeNs = System.nanoTime() - decodeStartNs

        val rowsCount = rows.size
        val totalNs = System.nanoTime() - totalStartNs
        val metrics = SqlQueryMetrics(
            status = response.status.value,
            sqlChars = sqlChars,
            rowsCount = rowsCount,
            httpRoundTripNs = httpRoundTripNs,
            bodyReadNs = bodyReadNs,
            jsonDecodeNs = decodeNs,
            totalNs = totalNs,
            responseBodyChars = bodyChars,
            errorBodyChars = null
        )
        logger.info(
            "TEMP_PERF|component=druid.query|operation=sql_request|status={}|sql_chars={}|rows_count={}|http_round_trip_ns={}|http_round_trip_ms={}|body_read_ns={}|body_read_ms={}|json_decode_ns={}|json_decode_ms={}|total_ns={}|total_ms={}|avg_per_row_ms={}|response_body_chars={}",
            metrics.status,
            metrics.sqlChars,
            metrics.rowsCount,
            metrics.httpRoundTripNs,
            metrics.httpRoundTripMs,
            metrics.bodyReadNs,
            metrics.bodyReadMs,
            metrics.jsonDecodeNs,
            metrics.jsonDecodeMs,
            metrics.totalNs,
            metrics.totalMs,
            metrics.avgPerRowMs,
            metrics.responseBodyChars ?: 0
        )
        return SqlQueryAttempt(
            success = true,
            rows = rows,
            metrics = metrics
        )
    }
    
    /**
     * Загружает записи в Druid datasource.
     * 
     * Автоматически разбивает данные на batch по config.batchSize
     * и отправляет отдельные ingestion tasks на Overlord.
     * 
     * Процесс загрузки:
     * 1. Разбивка записей на batch
     * 2. Для каждого batch → создание ingestion spec
     * 3. Отправка task на Overlord
     * 4. Логирование taskId
     * 
     * @param dataSource Имя целевого datasource
     * @param records Список записей для загрузки
     * @throws DruidException при ошибке ingestion
     */
    suspend fun ingest(dataSource: String, records: List<Map<String, Any?>>) {
        if (records.isEmpty()) {
            logger.warn("No records to ingest for dataSource: $dataSource")
            return
        }
        
        logger.info("Ingesting ${records.size} records to dataSource: $dataSource")

        // Метрика: старт таймера полного цикла ingest() по datasource.
        val ingestStartNs = System.nanoTime()
        // Метрика: старт таймера разбиения на батчи.
        val splitStartNs = System.nanoTime()
        // Разбиваем на batch по количеству + ограничению размера inline NDJSON.
        val batches = splitIntoSafeBatches(records)
        // Метрика: длительность разбиения входных данных на батчи.
        val splitNs = System.nanoTime() - splitStartNs

        // Метрика: суммарное время подготовки ingestion spec по всем батчам.
        var sumSpecBuildNs = 0L
        // Метрика: суммарное сетевое время POST /task по всем батчам.
        var sumHttpRoundTripNs = 0L
        // Метрика: суммарное время разбора ответов Overlord по всем батчам.
        var sumResponseDecodeNs = 0L
        // Метрика: минимальное время отправки одного батча.
        var minBatchTotalNs = Long.MAX_VALUE
        // Метрика: максимальное время отправки одного батча.
        var maxBatchTotalNs = 0L
        batches.forEachIndexed { index, batch ->
            logger.debug("Processing batch ${index + 1}/${batches.size} with ${batch.size} records")
            // Метрика: время отправки конкретного батча в Overlord с retry
            // при временной невозможности назначить задачу в кластере.
            val submissionMetrics = submitBatchWithAssignRetry(
                dataSource = dataSource,
                batch = batch,
                batchIndex = index + 1,
                batchCount = batches.size
            )
            // Агрегируем временные метрики по всем батчам для итогового анализа.
            sumSpecBuildNs += submissionMetrics.specBuildNs
            sumHttpRoundTripNs += submissionMetrics.httpRoundTripNs
            sumResponseDecodeNs += submissionMetrics.responseDecodeNs
            minBatchTotalNs = kotlin.math.min(minBatchTotalNs, submissionMetrics.totalNs)
            maxBatchTotalNs = kotlin.math.max(maxBatchTotalNs, submissionMetrics.totalNs)

            // Структурированный TEMP_PERF-лог по каждому батчу ingestion.
            logger.info(
                "TEMP_PERF|component=druid.ingest|operation=batch_submit|data_source={}|batch_index={}|batch_count={}|batch_records={}|task_id={}|spec_build_ns={}|spec_build_ms={}|http_round_trip_ns={}|http_round_trip_ms={}|response_decode_ns={}|response_decode_ms={}|batch_total_ns={}|batch_total_ms={}",
                dataSource,
                index + 1,
                batches.size,
                batch.size,
                submissionMetrics.taskId ?: "unknown",
                submissionMetrics.specBuildNs,
                submissionMetrics.specBuildNs / 1_000_000.0,
                submissionMetrics.httpRoundTripNs,
                submissionMetrics.httpRoundTripNs / 1_000_000.0,
                submissionMetrics.responseDecodeNs,
                submissionMetrics.responseDecodeNs / 1_000_000.0,
                submissionMetrics.totalNs,
                submissionMetrics.totalNs / 1_000_000.0
            )
        }
        
        // Метрика: полная длительность ingest() по datasource.
        val ingestTotalNs = System.nanoTime() - ingestStartNs
        // Метрика: средняя длительность отправки одного батча.
        val avgBatchMs = if (batches.isNotEmpty()) (ingestTotalNs / 1_000_000.0) / batches.size else 0.0
        // Метрика: количество записей в секунду на уровне submit ingestion tasks.
        val recordsPerSec = if (ingestTotalNs > 0) records.size.toDouble() * 1_000_000_000.0 / ingestTotalNs else 0.0
        // Метрика: количество батчей в секунду.
        val batchesPerSec = if (ingestTotalNs > 0) batches.size.toDouble() * 1_000_000_000.0 / ingestTotalNs else 0.0

        // Структурированный TEMP_PERF-лог агрегатов по ingest() для datasource.
        logger.info(
            "TEMP_PERF|component=druid.ingest|operation=ingest_summary|data_source={}|records_total={}|batch_size_config={}|batches_total={}|split_ns={}|split_ms={}|sum_spec_build_ns={}|sum_spec_build_ms={}|sum_http_round_trip_ns={}|sum_http_round_trip_ms={}|sum_response_decode_ns={}|sum_response_decode_ms={}|min_batch_total_ns={}|min_batch_total_ms={}|max_batch_total_ns={}|max_batch_total_ms={}|ingest_total_ns={}|ingest_total_ms={}|avg_batch_ms={}|records_per_sec={}|batches_per_sec={}",
            dataSource,
            records.size,
            config.batchSize,
            batches.size,
            splitNs,
            splitNs / 1_000_000.0,
            sumSpecBuildNs,
            sumSpecBuildNs / 1_000_000.0,
            sumHttpRoundTripNs,
            sumHttpRoundTripNs / 1_000_000.0,
            sumResponseDecodeNs,
            sumResponseDecodeNs / 1_000_000.0,
            if (batches.isNotEmpty()) minBatchTotalNs else 0L,
            if (batches.isNotEmpty()) minBatchTotalNs / 1_000_000.0 else 0.0,
            if (batches.isNotEmpty()) maxBatchTotalNs else 0L,
            if (batches.isNotEmpty()) maxBatchTotalNs / 1_000_000.0 else 0.0,
            ingestTotalNs,
            ingestTotalNs / 1_000_000.0,
            avgBatchMs,
            recordsPerSec,
            batchesPerSec
        )

        logger.info("Successfully submitted ${batches.size} ingestion batches for dataSource: $dataSource")
    }

    private suspend fun submitBatchWithAssignRetry(
        dataSource: String,
        batch: List<Map<String, Any?>>,
        batchIndex: Int,
        batchCount: Int
    ): IngestionTaskSubmissionMetrics {
        val retryCount = config.ingestionAssignRetryCount.coerceAtLeast(0)
        val retryDelayMs = config.ingestionAssignRetryDelayMs.coerceAtLeast(100L)
        var attempt = 0

        while (true) {
            attempt += 1
            val submissionMetrics = submitIngestionTask(dataSource, batch)
            val taskId = submissionMetrics.taskId

            if (config.awaitIngestionTasks && !taskId.isNullOrBlank()) {
                try {
                    awaitIngestionTasksCompletion(dataSource, listOf(taskId))
                } catch (e: DruidException) {
                    val message = e.message.orEmpty()
                    val assignFailed = message.contains("Failed to assign this task", ignoreCase = true)
                    val canRetry = assignFailed && attempt <= retryCount
                    if (canRetry) {
                        logger.warn(
                            "Retrying ingestion batch due to task assignment failure: dataSource={}, batch={}/{}, attempt={}/{}, delay_ms={}",
                            dataSource,
                            batchIndex,
                            batchCount,
                            attempt,
                            retryCount + 1,
                            retryDelayMs
                        )
                        delay(retryDelayMs)
                        continue
                    }
                    throw e
                }
            }
            return submissionMetrics
        }
    }

    private suspend fun awaitIngestionTasksCompletion(dataSource: String, taskIds: List<String>) {
        val timeoutMs = config.ingestionTaskTimeoutMs.coerceAtLeast(1_000L)
        val pollMs = config.ingestionTaskPollIntervalMs.coerceAtLeast(200L)
        val startedNs = System.nanoTime()
        val pending = taskIds.toMutableSet()

        logger.info(
            "Awaiting ingestion tasks completion: dataSource={}, tasks={}, timeout_ms={}, poll_ms={}",
            dataSource,
            taskIds.size,
            timeoutMs,
            pollMs
        )

        while (pending.isNotEmpty()) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            if (elapsedMs > timeoutMs) {
                throw DruidException(
                    "Ingestion tasks timeout for dataSource '$dataSource': pending=${pending.size}/${taskIds.size}"
                )
            }

            val completedInIteration = mutableListOf<String>()
            for (taskId in pending) {
                val status = getTaskStatus(taskId)
                when {
                    status.isSuccess() -> completedInIteration.add(taskId)
                    status.isFailed() -> {
                        throw DruidException(
                            "Ingestion task failed for dataSource '$dataSource': taskId=$taskId, error=${status.errorMsg ?: "unknown"}"
                        )
                    }
                }
            }
            completedInIteration.forEach { pending.remove(it) }

            logger.info(
                "TEMP_PERF|component=druid.ingest|operation=await_tasks_progress|data_source={}|tasks_total={}|tasks_pending={}|tasks_completed={}|tasks_failed={}|elapsed_ms={}",
                dataSource,
                taskIds.size,
                pending.size,
                taskIds.size - pending.size,
                0,
                elapsedMs
            )

            if (pending.isNotEmpty()) {
                delay(pollMs)
            }
        }

        val totalMs = (System.nanoTime() - startedNs) / 1_000_000.0
        logger.info(
            "TEMP_PERF|component=druid.ingest|operation=await_tasks_done|data_source={}|tasks_total={}|elapsed_ms={}",
            dataSource,
            taskIds.size,
            totalMs
        )
    }
    
    /**
     * Отправляет ingestion task на Overlord.
     * 
     * Создаёт inline ingestion spec и отправляет POST-запрос
     * на /druid/indexer/v1/task.
     * 
     * @param dataSource Имя datasource
     * @param records Batch записей
     * @throws DruidException при ошибке
     */
    private suspend fun submitIngestionTask(dataSource: String, records: List<Map<String, Any?>>): IngestionTaskSubmissionMetrics {
        // Метрика: старт таймера отправки одного ingestion batch.
        val totalStartNs = System.nanoTime()
        // Метрика: старт таймера генерации ingestion spec для батча.
        val specBuildStartNs = System.nanoTime()
        val ingestionSpec = createBatchIngestionSpec(dataSource, records)
        // Метрика: длительность подготовки ingestion spec.
        val specBuildNs = System.nanoTime() - specBuildStartNs
        val ingestionSpecBytes = objectMapper.writeValueAsBytes(ingestionSpec).size
        if (ingestionSpecBytes > MAX_SAFE_TASK_SPEC_BYTES) {
            throw DruidException(
                "Ingestion task spec is too large for ZooKeeper: dataSource='$dataSource', " +
                    "spec_bytes=$ingestionSpecBytes, safe_limit=$MAX_SAFE_TASK_SPEC_BYTES. " +
                    "Reduce DRUID_MAX_INLINE_BYTES/batch size or use non-inline inputSource."
            )
        }

        // Метрика: старт таймера HTTP POST на Overlord.
        val httpStartNs = System.nanoTime()
        val response: HttpResponse
        val httpRoundTripNs: Long
        try {
            response = requestWithFailover(
                pool = overlordEndpoints,
                path = "/druid/indexer/v1/task"
            ) { baseUrl ->
                postIndexerTaskWithRedirect(baseUrl, ingestionSpec)
            }
            // Метрика: длительность сетевого round-trip отправки task.
            httpRoundTripNs = System.nanoTime() - httpStartNs
        } catch (e: java.io.IOException) {
            val totalNs = System.nanoTime() - totalStartNs
            logger.info(
                "TEMP_PERF|component=druid.ingest|operation=batch_submit_io_failed|data_source={}|batch_records={}|spec_build_ns={}|spec_build_ms={}|total_ns={}|total_ms={}|exception={}",
                dataSource,
                records.size,
                specBuildNs,
                specBuildNs / 1_000_000.0,
                totalNs,
                totalNs / 1_000_000.0,
                e.javaClass.simpleName
            )
            throw DruidException("Ingestion failed (I/O): ${e.message}", e.toString())
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            // Метрика: полная длительность отправки батча до ошибки.
            val totalNs = System.nanoTime() - totalStartNs
            // Структурированный TEMP_PERF-лог ошибки отправки ingestion batch.
            logger.info(
                "TEMP_PERF|component=druid.ingest|operation=batch_submit_failed|data_source={}|batch_records={}|status={}|spec_build_ns={}|spec_build_ms={}|http_round_trip_ns={}|http_round_trip_ms={}|total_ns={}|total_ms={}|error_body_chars={}",
                dataSource,
                records.size,
                response.status.value,
                specBuildNs,
                specBuildNs / 1_000_000.0,
                httpRoundTripNs,
                httpRoundTripNs / 1_000_000.0,
                totalNs,
                totalNs / 1_000_000.0,
                errorBody.length
            )
            logger.error("Ingestion failed with status ${response.status}: $errorBody")
            throw DruidException("Ingestion failed: ${response.status}", errorBody)
        }

        // Метрика: старт таймера разбора тела ответа Overlord.
        val decodeStartNs = System.nanoTime()
        val result: Map<String, Any?> = objectMapper.readValue(response.bodyAsText())
        // Метрика: длительность разбора ответа Overlord.
        val responseDecodeNs = System.nanoTime() - decodeStartNs
        val taskId = result["task"] as? String
        // Метрика: полная длительность отправки ingestion batch.
        val totalNs = System.nanoTime() - totalStartNs
        logger.info("Submitted ingestion task: $taskId")

        return IngestionTaskSubmissionMetrics(
            taskId = taskId,
            specBuildNs = specBuildNs,
            httpRoundTripNs = httpRoundTripNs,
            responseDecodeNs = responseDecodeNs,
            totalNs = totalNs
        )
    }
    
    /**
     * Делит входные записи на батчи, ограничивая:
     * - максимальный размер батча по количеству записей ([config.batchSize])
     * - максимальный размер inline NDJSON payload ([config.maxInlineBytes])
     *
     * Это снижает шанс сетевых ошибок при отправке ingestion spec (например, "Broken pipe").
     */
    private fun splitIntoSafeBatches(records: List<Map<String, Any?>>): List<List<Map<String, Any?>>> {
        if (records.isEmpty()) return emptyList()

        val maxRecords = config.batchSize.coerceAtLeast(1)
        val configuredMaxBytes = config.maxInlineBytes.coerceAtLeast(10_000)
        val maxBytes = kotlin.math.min(configuredMaxBytes, 256_000)
        if (configuredMaxBytes > maxBytes) {
            logger.warn(
                "Configured maxInlineBytes={} is too high for stable ZooKeeper task announce; using safety cap={} bytes",
                configuredMaxBytes,
                maxBytes
            )
        }

        val result = ArrayList<List<Map<String, Any?>>>(kotlin.math.max(1, records.size / maxRecords))
        val current = ArrayList<Map<String, Any?>>(kotlin.math.min(maxRecords, records.size))
        var currentBytes = 0

        for (r in records) {
            // Оценка размера одной строки NDJSON.
            val line = objectMapper.writeValueAsString(r)
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1 // + newline

            val wouldExceedCount = current.size >= maxRecords
            val wouldExceedBytes = current.isNotEmpty() && (currentBytes + lineBytes > maxBytes)

            if (wouldExceedCount || wouldExceedBytes) {
                result.add(current.toList())
                current.clear()
                currentBytes = 0
            }

            current.add(r)
            currentBytes += lineBytes
        }

        if (current.isNotEmpty()) result.add(current.toList())
        return result
    }

    /**
     * Создаёт спецификацию для inline batch ingestion.
     * 
     * Формат: index_parallel с inline inputSource.
     * Данные передаются как NDJSON (newline-delimited JSON).
     * 
     * Структура spec:
     * - dataSchema: схема данных (dimensions, timestamp)
     * - ioConfig: источник данных (inline JSON)
     * - tuningConfig: параметры индексации
     * 
     * @param dataSource Имя datasource
     * @param records Записи для загрузки
     * @return Map-представление ingestion spec
     */
    private fun createBatchIngestionSpec(dataSource: String, records: List<Map<String, Any?>>): Map<String, Any?> {
        // Извлекаем колонки из первой записи (кроме __time)
        val dimensionColumns = records.firstOrNull()?.keys
            ?.filter { it != "__time" }
            ?.toList() ?: emptyList()
        
        return mapOf(
            "type" to "index_parallel",
            "spec" to mapOf(
                "dataSchema" to mapOf(
                    "dataSource" to dataSource,
                    "timestampSpec" to mapOf(
                        "column" to "__time",
                        "format" to "millis"
                    ),
                    "dimensionsSpec" to mapOf(
                        "dimensions" to dimensionColumns.map { col ->
                            mapOf(
                                "name" to col,
                                "type" to inferDruidType(records.firstOrNull()?.get(col))
                            )
                        }
                    ),
                    "granularitySpec" to mapOf(
                        "type" to "uniform",
                        "segmentGranularity" to "DAY",
                        "queryGranularity" to "NONE",
                        "rollup" to false
                    )
                ),
                "ioConfig" to mapOf(
                    "type" to "index_parallel",
                    "inputSource" to mapOf(
                        "type" to "inline",
                        // NDJSON формат: каждая запись на отдельной строке
                        "data" to records.joinToString("\n") { objectMapper.writeValueAsString(it) }
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
    }
    
    /**
     * Определяет тип Druid для значения.
     * 
     * Маппинг:
     * - Long, Int → "long"
     * - Double, Float → "double"
     * - всё остальное → "string"
     * 
     * @param value Значение для анализа
     * @return Строка типа Druid
     */
    private fun inferDruidType(value: Any?): String {
        return when (value) {
            is Long, is Int -> "long"
            is Double, is Float -> "double"
            else -> "string"
        }
    }
    
    /**
     * Создаёт datasource из IngestionSpec.
     * 
     * Отправляет полную спецификацию на Overlord.
     * Используется для создания datasource с предопределённой схемой.
     * 
     * @param spec Спецификация ingestion
     * @throws DruidException при ошибке создания
     * 
     * @see IngestionSpec структура спецификации
     */
    suspend fun createDataSource(spec: IngestionSpec) {
        logger.info("Creating dataSource with spec: ${spec.dataSource}")
        
        val response: HttpResponse = requestWithFailover(
            pool = overlordEndpoints,
            path = "/druid/indexer/v1/task"
        ) { baseUrl ->
            postIndexerTaskWithRedirect(baseUrl, spec.toMap())
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("DataSource creation failed: $errorBody")
            throw DruidException("DataSource creation failed: ${response.status}", errorBody)
        }
        
        val result: Map<String, Any?> = objectMapper.readValue(response.bodyAsText())
        logger.info("DataSource creation task submitted: ${result["task"]}")
    }

    private suspend fun postIndexerTaskWithRedirect(baseUrl: String, body: Any): HttpResponse {
        val requestUrl = "$baseUrl/druid/indexer/v1/task"
        val initialResponse = httpClient.post(requestUrl) {
            setBody(body)
        }
        if (initialResponse.status == HttpStatusCode.TemporaryRedirect || initialResponse.status == HttpStatusCode.PermanentRedirect) {
            val location = initialResponse.headers[HttpHeaders.Location]
            if (!location.isNullOrBlank()) {
                val redirectedUrl = resolveRedirectUrl(requestUrl, location)
                logger.warn(
                    "Druid overlord redirected {} -> {} (status {})",
                    requestUrl,
                    redirectedUrl,
                    initialResponse.status.value
                )
                overlordEndpoints.markPreferredByUrl(redirectedUrl)
                return httpClient.post(redirectedUrl) {
                    setBody(body)
                }
            }
            logger.warn(
                "Druid overlord returned redirect status {} for {} without Location header",
                initialResponse.status.value,
                requestUrl
            )
        }
        return initialResponse
    }

    private fun resolveRedirectUrl(baseUrl: String, location: String): String {
        return try {
            URI(baseUrl).resolve(location).toString()
        } catch (_: Exception) {
            location
        }
    }
    
    /**
     * Получает статус задачи индексации.
     * 
     * Запрашивает Overlord API для проверки состояния task.
     * Используется для мониторинга прогресса ingestion.
     * 
     * Возможные статусы:
     * - RUNNING: задача выполняется
     * - SUCCESS: задача успешно завершена
     * - FAILED: задача завершилась с ошибкой
     * 
     * @param taskId ID задачи (возвращается при submit)
     * @return TaskStatus с деталями
     * @throws DruidException при ошибке запроса
     */
    suspend fun getTaskStatus(taskId: String): TaskStatus {
        val response: HttpResponse = requestWithFailover(
            pool = overlordEndpoints,
            path = "/druid/indexer/v1/task/$taskId/status"
        ) { baseUrl ->
            httpClient.get("$baseUrl/druid/indexer/v1/task/$taskId/status")
        }
        
        if (!response.status.isSuccess()) {
            throw DruidException("Failed to get task status", response.bodyAsText())
        }
        
        val result: Map<String, Any?> = objectMapper.readValue(response.bodyAsText())
        val status = result["status"] as? Map<*, *>
        
        return TaskStatus(
            id = taskId,
            status = status?.get("status")?.toString() ?: "UNKNOWN",
            duration = (status?.get("duration") as? Number)?.toLong() ?: 0,
            errorMsg = status?.get("errorMsg")?.toString()
        )
    }
    
    /**
     * Получает список всех datasource в кластере.
     * 
     * Запрашивает Coordinator API.
     * 
     * @return Список имён datasource
     * @throws DruidException при ошибке запроса
     */
    suspend fun listDataSources(): List<String> {
        val response: HttpResponse = requestWithFailover(
            pool = coordinatorEndpoints,
            path = "/druid/coordinator/v1/datasources"
        ) { baseUrl ->
            httpClient.get("$baseUrl/druid/coordinator/v1/datasources")
        }
        
        if (!response.status.isSuccess()) {
            throw DruidException("Failed to list datasources", response.bodyAsText())
        }
        
        return objectMapper.readValue(response.bodyAsText())
    }
    
    /**
     * Удаляет datasource из кластера.
     * 
     * ВНИМАНИЕ: Удаляет все данные datasource!
     * Логирует предупреждение перед удалением.
     * 
     * @param dataSource Имя datasource для удаления
     * @throws DruidException при ошибке удаления
     */
    suspend fun deleteDataSource(dataSource: String) {
        logger.warn("Deleting dataSource: $dataSource")
        
        val response: HttpResponse = requestWithFailover(
            pool = coordinatorEndpoints,
            path = "/druid/coordinator/v1/datasources/$dataSource"
        ) { baseUrl ->
            httpClient.delete("$baseUrl/druid/coordinator/v1/datasources/$dataSource")
        }
        
        if (!response.status.isSuccess()) {
            throw DruidException("Failed to delete dataSource", response.bodyAsText())
        }
        
        logger.info("DataSource deleted: $dataSource")
    }
    
    /**
     * Закрывает HTTP-клиент и освобождает ресурсы.
     * 
     * Должен вызываться при завершении работы с клиентом.
     */
    override fun close() {
        httpClient.close()
    }

    private fun configureJvmTrustStore(config: DruidConfig) {
        if (config.insecureSkipTlsVerify) {
            logger.warn("TLS insecure mode enabled; JVM trustStore auto-configuration is skipped.")
            return
        }

        val trustStorePath = config.trustStorePath?.takeIf { it.isNotBlank() } ?: return
        val trustStoreType = config.trustStoreType.ifBlank { "PKCS12" }
        val trustStorePassword = config.trustStorePassword.orEmpty()

        // CIO/JSSE may use default JVM SSL context. Keep it aligned with app config.
        System.setProperty("javax.net.ssl.trustStore", trustStorePath)
        System.setProperty("javax.net.ssl.trustStoreType", trustStoreType)
        if (trustStorePassword.isNotEmpty()) {
            System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword)
        }

        logger.info(
            "JVM trustStore configured: path={}, type={}, passwordSet={}",
            trustStorePath,
            trustStoreType,
            trustStorePassword.isNotEmpty()
        )
    }

    private fun createTrustManager(config: DruidConfig): X509TrustManager? {
        if (config.insecureSkipTlsVerify) {
            logger.warn("TLS certificate verification is DISABLED (insecureSkipTlsVerify=true). Use only in dev/test.")
            return trustAllManager
        }

        val trustStorePath = config.trustStorePath?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val trustStore = KeyStore.getInstance(config.trustStoreType)
            FileInputStream(trustStorePath).use { input ->
                trustStore.load(input, config.trustStorePassword?.toCharArray())
            }

            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
            tmf.init(trustStore)
            val manager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                ?: throw IllegalStateException("No X509TrustManager in TrustManagerFactory")

            val pinnedFingerprints = linkedSetOf<String>()
            val pinnedSubjects = linkedSetOf<String>()
            val expectedOverlordHost = runCatching { URI(config.overlordUrl).host }.getOrNull()
            val aliases = trustStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = trustStore.getCertificate(alias) as? X509Certificate ?: continue
                pinnedFingerprints += sha256Hex(cert)
                pinnedSubjects += cert.subjectX500Principal.name
            }

            logger.info(
                "TLS trustStore loaded: path={}, type={}, acceptedIssuers={}",
                trustStorePath,
                config.trustStoreType,
                manager.acceptedIssuers.size
            )

            // Debug aid: show what trust anchors JSSE actually sees.
            // This helps diagnose PKIX failures on remote Druid endpoints.
            val acceptedIssuers = manager.acceptedIssuers
            if (acceptedIssuers.isNotEmpty()) {
                acceptedIssuers.forEachIndexed { idx, cert ->
                    logger.info(
                        "TLS trustStore acceptedIssuer[{}]: subject='{}', issuer='{}'",
                        idx,
                        cert.subjectX500Principal.name,
                        cert.issuerX500Principal.name
                    )
                }
            } else {
                logger.info("TLS trustStore acceptedIssuer list is empty (unexpected unless truststore is wrong).")
            }
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = manager.acceptedIssuers

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    manager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        manager.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        val leaf = chain.firstOrNull()
                        val leafFp = leaf?.let { sha256Hex(it) }
                        val chainFingerprints = chain.map { sha256Hex(it) }
                        val chainMatchesPinnedFingerprint = chainFingerprints.any { pinnedFingerprints.contains(it) }
                        val issuerMatchesPinnedSubject = chain.any { pinnedSubjects.contains(it.issuerX500Principal.name) }
                        val leafMatchesExpectedHost = leaf?.matchesHost(expectedOverlordHost) == true

                        if (
                            leaf != null &&
                            (
                                (leafFp != null && pinnedFingerprints.contains(leafFp)) ||
                                    chainMatchesPinnedFingerprint ||
                                    issuerMatchesPinnedSubject ||
                                    leafMatchesExpectedHost
                                )
                        ) {
                            logger.warn(
                                "TLS PKIX failed; accepting fallback based on truststore pin match. " +
                                    "leafSubject='{}', leafIssuer='{}', leafSha256={}, " +
                                    "chainMatchByFingerprint={}, chainMatchByIssuerSubject={}, leafMatchesExpectedHost={}",
                                leaf.subjectX500Principal.name,
                                leaf.issuerX500Principal.name,
                                leafFp,
                                chainMatchesPinnedFingerprint,
                                issuerMatchesPinnedSubject,
                                leafMatchesExpectedHost
                            )
                            return
                        }
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            throw DruidException(
                "Failed to initialize TLS trustStore from '$trustStorePath': ${e.message}",
                e.toString()
            )
        }
    }

    private fun sha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun X509Certificate.matchesHost(expectedHost: String?): Boolean {
        if (expectedHost.isNullOrBlank()) return false
        val expected = expectedHost.lowercase()

        val sans = runCatching { subjectAlternativeNames }.getOrNull()
        if (sans != null) {
            for (entry in sans) {
                val type = entry?.getOrNull(0) as? Int ?: continue
                val value = (entry.getOrNull(1) as? String)?.lowercase() ?: continue
                if (type == 2 && (value == expected || (value.startsWith("*.") && expected.endsWith(value.removePrefix("*"))))) {
                    return true
                }
            }
        }

        val subject = subjectX500Principal.name.lowercase()
        return subject.contains("cn=$expected")
    }

    private val trustAllManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }

    /**
     * Детализированные временные метрики отправки одного ingestion batch.
     *
     * @property taskId ID задачи в Overlord (если вернулся в ответе)
     * @property specBuildNs Длительность генерации ingestion spec (ns)
     * @property httpRoundTripNs Длительность HTTP round-trip POST /task (ns)
     * @property responseDecodeNs Длительность разбора JSON-ответа Overlord (ns)
     * @property totalNs Полная длительность submitIngestionTask() (ns)
     */
    private data class IngestionTaskSubmissionMetrics(
        val taskId: String?,
        val specBuildNs: Long,
        val httpRoundTripNs: Long,
        val responseDecodeNs: Long,
        val totalNs: Long
    )

    private data class EndpointPool(
        val name: String,
        val urls: List<String>
    ) {
        private val preferredIndex = AtomicInteger(0)

        init {
            require(urls.isNotEmpty()) { "Endpoint list for '$name' is empty" }
        }

        fun orderedCandidates(): List<String> {
            if (urls.size <= 1) return urls
            val start = preferredIndex.get().coerceIn(0, urls.lastIndex)
            return (0 until urls.size).map { urls[(start + it) % urls.size] }
        }

        fun markPreferred(baseUrl: String) {
            val idx = urls.indexOfFirst { normalizeBaseUrl(it) == normalizeBaseUrl(baseUrl) }
            if (idx >= 0) preferredIndex.set(idx)
        }

        fun markPreferredByUrl(url: String) {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return
            val matched = urls.firstOrNull { candidate ->
                runCatching { URI(candidate).host }
                    .getOrNull()
                    ?.lowercase() == host
            } ?: return
            markPreferred(matched)
        }

        private fun normalizeBaseUrl(url: String): String = url.trimEnd('/').lowercase()
    }

    private suspend fun requestWithFailover(
        pool: EndpointPool,
        path: String,
        request: suspend (baseUrl: String) -> HttpResponse
    ): HttpResponse {
        val rounds = if (pool.urls.size > 1) 2 else 1
        var lastNetworkError: Exception? = null
        var lastResponse: HttpResponse? = null

        repeat(rounds) { round ->
            // Primary-first failover: always try hosts in config order (e.g. 09 -> 10).
            val candidates = pool.orderedCandidates()
            for ((index, baseUrl) in candidates.withIndex()) {
                try {
                    val response = request(baseUrl)
                    val finalUrl = runCatching { response.request.url.toString() }.getOrNull()
                    if (!finalUrl.isNullOrBlank()) {
                        pool.markPreferredByUrl(finalUrl)
                    } else {
                        pool.markPreferred(baseUrl)
                    }
                    val retriable = response.status.value == 429 || response.status.value in 500..599
                    if (!retriable) {
                        return response
                    }
                    lastResponse = response
                    logger.warn(
                        "Druid {} endpoint returned retriable status {} for path {} (host {} of {}, round {} of {})",
                        pool.name,
                        response.status.value,
                        path,
                        index + 1,
                        candidates.size,
                        round + 1,
                        rounds
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastNetworkError = e
                    logger.warn(
                        "Druid {} endpoint network error for path {} (host {} of {}, round {} of {}): {} ({})",
                        pool.name,
                        path,
                        index + 1,
                        candidates.size,
                        round + 1,
                        rounds,
                        e.javaClass.simpleName,
                        e.message
                    )
                }
            }
            if (round + 1 < rounds) {
                delay(200L * (round + 1))
            }
        }

        if (lastResponse != null) return lastResponse as HttpResponse
        val causeSuffix = lastNetworkError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown"
        throw java.io.IOException(
            "No Druid ${pool.name} endpoints available for path: $path (last_error=$causeSuffix)",
            lastNetworkError
        )
    }
}

/**
 * Статус задачи индексации Druid.
 * 
 * @property id Уникальный ID задачи
 * @property status Текстовый статус (RUNNING, SUCCESS, FAILED)
 * @property duration Длительность выполнения в миллисекундах
 * @property errorMsg Сообщение об ошибке (при FAILED)
 */
data class TaskStatus(
    val id: String,
    val status: String,
    val duration: Long,
    val errorMsg: String?
) {
    /** Проверяет, выполняется ли задача */
    fun isRunning() = status == "RUNNING"
    
    /** Проверяет, успешно ли завершена задача */
    fun isSuccess() = status == "SUCCESS"
    
    /** Проверяет, завершилась ли задача с ошибкой */
    fun isFailed() = status == "FAILED"
}

/**
 * Исключение при ошибке взаимодействия с Druid.
 * 
 * @property message Описание ошибки
 * @property responseBody Тело ответа сервера (для диагностики)
 */
class DruidException(message: String, val responseBody: String? = null) : Exception(message)
