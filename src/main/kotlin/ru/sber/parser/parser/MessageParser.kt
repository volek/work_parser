package ru.sber.parser.parser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
        return objectMapper.readValue(json)
    }
    
    /**
     * Парсит JSON-массив сообщений в список BpmMessage.
     * 
     * @param json JSON-массив "[{...}, {...}, ...]"
     * @return Список десериализованных объектов BpmMessage
     * @throws com.fasterxml.jackson.core.JsonParseException при невалидном JSON
     */
    fun parseList(json: String): List<BpmMessage> {
        return objectMapper.readValue(json)
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
