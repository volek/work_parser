package ru.sber.parser.druid

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import org.slf4j.LoggerFactory
import ru.sber.parser.config.DruidConfig
import java.io.Closeable

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
    
    /** Jackson ObjectMapper для сериализации/десериализации JSON */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
    
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
        install(ContentNegotiation) {
            jackson {
                registerModule(JavaTimeModule())
            }
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.readTimeout
            connectTimeoutMillis = config.connectTimeout
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
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
        
        val response: HttpResponse = httpClient.post("${config.routerUrl}/druid/v2/sql") {
            setBody(mapOf("query" to sql))
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Query failed with status ${response.status}: $errorBody")
            throw DruidException("Query failed: ${response.status}", errorBody)
        }
        
        val body = response.bodyAsText()
        return objectMapper.readValue(body)
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
        
        // Разбиваем на batch
        val batches = records.chunked(config.batchSize)
        batches.forEachIndexed { index, batch ->
            logger.debug("Processing batch ${index + 1}/${batches.size} with ${batch.size} records")
            submitIngestionTask(dataSource, batch)
        }
        
        logger.info("Successfully submitted ${batches.size} ingestion batches for dataSource: $dataSource")
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
    private suspend fun submitIngestionTask(dataSource: String, records: List<Map<String, Any?>>) {
        val ingestionSpec = createBatchIngestionSpec(dataSource, records)
        
        val response: HttpResponse = httpClient.post("${config.overlordUrl}/druid/indexer/v1/task") {
            setBody(ingestionSpec)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Ingestion failed with status ${response.status}: $errorBody")
            throw DruidException("Ingestion failed: ${response.status}", errorBody)
        }
        
        val result: Map<String, Any?> = objectMapper.readValue(response.bodyAsText())
        val taskId = result["task"] as? String
        logger.info("Submitted ingestion task: $taskId")
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
        
        val response: HttpResponse = httpClient.post("${config.overlordUrl}/druid/indexer/v1/task") {
            setBody(spec.toMap())
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("DataSource creation failed: $errorBody")
            throw DruidException("DataSource creation failed: ${response.status}", errorBody)
        }
        
        val result: Map<String, Any?> = objectMapper.readValue(response.bodyAsText())
        logger.info("DataSource creation task submitted: ${result["task"]}")
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
        val response: HttpResponse = httpClient.get("${config.overlordUrl}/druid/indexer/v1/task/$taskId/status")
        
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
        val response: HttpResponse = httpClient.get("${config.coordinatorUrl}/druid/coordinator/v1/datasources")
        
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
        
        val response: HttpResponse = httpClient.delete("${config.coordinatorUrl}/druid/coordinator/v1/datasources/$dataSource")
        
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
