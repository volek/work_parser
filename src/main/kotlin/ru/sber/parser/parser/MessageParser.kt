package ru.sber.parser.parser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.sber.parser.model.BpmMessage

/**
 * Парсер JSON-сообщений BPM-процессов.
 * 
 * Обеспечивает двунаправленную сериализацию:
 * - JSON → BpmMessage (десериализация входящих сообщений)
 * - BpmMessage → JSON (сериализация для логирования/отладки)
 * 
 * Конфигурация Jackson ObjectMapper:
 * - JavaTimeModule: поддержка java.time.* (OffsetDateTime, Instant)
 * - FAIL_ON_UNKNOWN_PROPERTIES=false: игнорирование неизвестных полей
 * - READ_DATE_TIMESTAMPS_AS_NANOSECONDS=false: миллисекунды вместо наносекунд
 * 
 * Использование:
 * ```kotlin
 * val parser = MessageParser()
 * 
 * // Парсинг одного сообщения
 * val message: BpmMessage = parser.parse(jsonString)
 * 
 * // Парсинг массива сообщений
 * val messages: List<BpmMessage> = parser.parseList(jsonArray)
 * 
 * // Сериализация в JSON
 * val json: String = parser.toJson(message)
 * ```
 * 
 * Форматы даты:
 * - ISO-8601: "2024-01-15T10:30:00+03:00"
 * - Unix timestamp (millis): 1705309800000
 * 
 * @see BpmMessage основная модель данных
 * @see VariableFlattener для работы с переменными
 */
class MessageParser {
    /** Логгер для временных метрик парсинга и сериализации JSON. */
    private val logger = LoggerFactory.getLogger(MessageParser::class.java)

    /**
     * Jackson ObjectMapper с настройками для Kotlin и Java Time.
     * 
     * Модули:
     * - KotlinModule (через jacksonObjectMapper): поддержка data class, null safety
     * - JavaTimeModule: сериализация OffsetDateTime, Instant, LocalDate
     * 
     * Настройки десериализации:
     * - Неизвестные поля игнорируются (расширяемость JSON-схемы)
     * - Временные метки читаются в миллисекундах (совместимость с Druid)
     */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
    
    /**
     * Парсит одиночное JSON-сообщение в BpmMessage.
     * 
     * @param json JSON-строка с данными BPM-процесса
     * @return Десериализованный объект BpmMessage
     * @throws com.fasterxml.jackson.core.JsonParseException при невалидном JSON
     * @throws com.fasterxml.jackson.databind.JsonMappingException при несоответствии схемы
     */
    fun parse(json: String): BpmMessage {
        // Метрика: размер входного JSON в символах (быстрый прокси объёма парсинга).
        val jsonChars = json.length
        // Метрика: общий размер входного JSON в байтах UTF-8 (для расчёта пропускной способности парсера).
        val jsonBytes = json.toByteArray(Charsets.UTF_8).size
        // Метрика: старт высокоточного таймера полного цикла parse().
        val totalStartNs = System.nanoTime()
        // Метрика: старт таймера чистой десериализации Jackson.
        val deserializeStartNs = System.nanoTime()
        val result = objectMapper.readValue<BpmMessage>(json)
        // Метрика: длительность чистой десериализации JSON -> BpmMessage.
        val deserializeNs = System.nanoTime() - deserializeStartNs
        // Метрика: полная длительность parse() с учётом обвязки метода.
        val totalNs = System.nanoTime() - totalStartNs
        // Метрика: пропускная способность парсера в МБ/с по входному payload.
        val throughputMbPerSec = if (totalNs > 0) (jsonBytes.toDouble() * 1_000_000_000.0 / totalNs) / (1024.0 * 1024.0) else 0.0

        // Структурированный TEMP_PERF-лог для последующего анализа во внешних системах.
        logger.info(
            "TEMP_PERF|component=parser|operation=parse_one|json_chars={}|json_bytes={}|deserialize_ns={}|deserialize_ms={}|total_ns={}|total_ms={}|throughput_mb_s={}",
            jsonChars,
            jsonBytes,
            deserializeNs,
            deserializeNs / 1_000_000.0,
            totalNs,
            totalNs / 1_000_000.0,
            throughputMbPerSec
        )

        return result
    }
    
    /**
     * Парсит JSON-массив сообщений в список BpmMessage.
     * 
     * @param json JSON-массив "[{...}, {...}, ...]"
     * @return Список десериализованных объектов BpmMessage
     * @throws com.fasterxml.jackson.core.JsonParseException при невалидном JSON
     */
    fun parseList(json: String): List<BpmMessage> {
        // Метрика: размер JSON-массива в символах.
        val jsonChars = json.length
        // Метрика: размер JSON-массива в байтах UTF-8.
        val jsonBytes = json.toByteArray(Charsets.UTF_8).size
        // Метрика: старт таймера полного parseList().
        val totalStartNs = System.nanoTime()
        // Метрика: старт таймера десериализации массива.
        val deserializeStartNs = System.nanoTime()
        val result = objectMapper.readValue<List<BpmMessage>>(json)
        // Метрика: длительность десериализации списка сообщений.
        val deserializeNs = System.nanoTime() - deserializeStartNs
        // Метрика: полная длительность parseList().
        val totalNs = System.nanoTime() - totalStartNs
        // Метрика: количество разобранных сообщений в массиве.
        val messagesCount = result.size
        // Метрика: средняя длительность разбора одного сообщения в массиве.
        val avgPerMessageMs = if (messagesCount > 0) (totalNs / 1_000_000.0) / messagesCount else 0.0
        // Метрика: пропускная способность в МБ/с.
        val throughputMbPerSec = if (totalNs > 0) (jsonBytes.toDouble() * 1_000_000_000.0 / totalNs) / (1024.0 * 1024.0) else 0.0

        // Структурированный TEMP_PERF-лог для анализа батчевого парсинга.
        logger.info(
            "TEMP_PERF|component=parser|operation=parse_list|json_chars={}|json_bytes={}|messages_count={}|deserialize_ns={}|deserialize_ms={}|total_ns={}|total_ms={}|avg_per_message_ms={}|throughput_mb_s={}",
            jsonChars,
            jsonBytes,
            messagesCount,
            deserializeNs,
            deserializeNs / 1_000_000.0,
            totalNs,
            totalNs / 1_000_000.0,
            avgPerMessageMs,
            throughputMbPerSec
        )

        return result
    }
    
    /**
     * Сериализует BpmMessage в JSON-строку.
     * 
     * Используется для:
     * - Логирования обработанных сообщений
     * - Отладки преобразований
     * - Сохранения в файл
     * 
     * @param message Объект BpmMessage для сериализации
     * @return JSON-строка (компактный формат, без форматирования)
     */
    fun toJson(message: BpmMessage): String {
        return objectMapper.writeValueAsString(message)
    }
    
    /**
     * Сериализует произвольный объект в JSON.
     * 
     * Универсальный метод для сериализации любых объектов:
     * - Map<String, Any?> для записей Druid
     * - List<*> для батчей данных
     * - Data classes для структурированных данных
     * 
     * @param any Любой сериализуемый объект
     * @return JSON-строка
     */
    fun toJson(any: Any): String {
        return objectMapper.writeValueAsString(any)
    }
    
    /**
     * Возвращает внутренний ObjectMapper.
     * 
     * Полезно для:
     * - Кастомной сериализации
     * - Работы с JsonNode напрямую
     * - Интеграции с другими библиотеками
     * 
     * @return Сконфигурированный ObjectMapper
     */
    fun getObjectMapper(): ObjectMapper = objectMapper
}
