package ru.sber.parser.model.druid

import ru.sber.parser.druid.DruidDataSources
import java.time.Instant

/**
 * EAV (Entity-Attribute-Value) модель данных для Apache Druid.
 * 
 * Этот подход реализует классическую EAV-схему, где:
 * - Entity (Сущность): процесс, идентифицируемый по processId
 * - Attribute (Атрибут): название переменной (varPath)
 * - Value (Значение): значение переменной (varValue)
 * 
 * Архитектура использует два источника данных:
 * 1. process_events - события/состояния процессов (узкая схема)
 * 2. process_variables - переменные процессов (EAV-схема)
 * 
 * Преимущества EAV-подхода:
 * - Гибкость: новые переменные добавляются без изменения схемы
 * - Масштабируемость: подходит для произвольного числа переменных
 * - Простота: понятная и предсказуемая структура данных
 * 
 * Недостатки:
 * - Требует JOIN для получения полного состояния процесса
 * - Большой объём данных (одна строка на каждую переменную)
 * - Сложные запросы для фильтрации по нескольким переменным
 */

/**
 * Запись события процесса для источника данных Druid.
 * 
 * Содержит только метаданные процесса без переменных.
 * Переменные хранятся отдельно в ProcessVariableRecord.
 * 
 * Эта модель подходит, когда:
 * - Количество переменных сильно варьируется между процессами
 * - Требуется гибкость в добавлении новых типов переменных
 * - Запросы чаще направлены на анализ событий, чем на поиск по переменным
 * 
 * @property processId Уникальный идентификатор экземпляра процесса
 * @property timestamp Временная метка события
 */
data class ProcessEventRecord(
    /** Уникальный идентификатор экземпляра процесса */
    val processId: String,
    
    /** 
     * Временная метка события процесса.
     * Используется Druid для партиционирования по времени.
     * Соответствует дате создания или последнего обновления процесса.
     */
    val timestamp: Instant,
    
    /** 
     * Название бизнес-процесса.
     * Например: "processClientRequest", "handleComplaint", "processPayment"
     */
    val processName: String,
    
    /** 
     * Числовой код состояния процесса:
     * - 0: PENDING - процесс создан, но не запущен
     * - 1: ACTIVE - процесс выполняется
     * - 2: COMPLETED - процесс успешно завершён
     * - 3: ABORTED - процесс прерван из-за ошибки
     * - 4: SUSPENDED - процесс приостановлен
     */
    val state: Int,
    
    /** 
     * Идентификатор модуля/подсистемы.
     * Используется для фильтрации процессов по принадлежности к модулю.
     */
    val moduleId: String?,
    
    /** 
     * Бизнес-ключ для корреляции с внешними системами.
     * Позволяет связать процесс с заявкой, договором или другим бизнес-объектом.
     */
    val businessKey: String?,
    
    /** 
     * Идентификатор корневого процесса в иерархии.
     * Для корневых процессов равен null или собственному processId.
     */
    val rootInstanceId: String?,
    
    /** 
     * Идентификатор непосредственного родительского процесса.
     * null для процессов верхнего уровня.
     */
    val parentInstanceId: String?,
    
    /** 
     * Номер версии определения процесса (BPMN-диаграммы).
     * Позволяет отслеживать, какая версия процесса была выполнена.
     */
    val version: Int?,
    
    /** 
     * Дата и время завершения процесса.
     * null, если процесс ещё активен или приостановлен.
     */
    val endDate: Instant?,
    
    /** 
     * Сообщение об ошибке, если процесс завершился аварийно.
     * Содержит stack trace или описание бизнес-ошибки.
     */
    val error: String?,
    
    /** 
     * JSON-массив с информацией о выполненных узлах (шагах) процесса.
     * 
     * Структура элемента массива:
     * {
     *   "nodeId": "идентификатор узла в BPMN",
     *   "nodeName": "человекочитаемое название",
     *   "nodeType": "тип узла (userTask, serviceTask, gateway и т.д.)",
     *   "startDate": "дата начала выполнения",
     *   "endDate": "дата завершения (null если активен)",
     *   "slaDate": "дедлайн выполнения",
     *   "iteration": "номер итерации (для циклов)"
     * }
     */
    val nodeInstancesJson: String
) {
    /**
     * Преобразует запись в Map для отправки в Druid.
     * 
     * @return Map, где ключи соответствуют колонкам в Druid datasource
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "process_id" to processId,
        "__time" to timestamp.toEpochMilli(),  // Специальная колонка Druid для времени
        "process_name" to processName,
        "state" to state,
        "module_id" to moduleId,
        "business_key" to businessKey,
        "root_instance_id" to rootInstanceId,
        "parent_instance_id" to parentInstanceId,
        "version" to version,
        "end_date" to endDate?.toEpochMilli(),
        "error" to error,
        "node_instances_json" to nodeInstancesJson
    )
    
    companion object {
        /** Название источника данных в Druid для событий процессов */
        const val DATA_SOURCE_NAME = DruidDataSources.Eav.EVENTS
    }
}

/**
 * Запись переменной процесса в формате EAV.
 * 
 * Каждая переменная процесса хранится как отдельная строка.
 * Это позволяет хранить произвольное количество переменных
 * без изменения схемы базы данных.
 * 
 * Пример: процесс с 50 переменными создаст 50 записей в этой таблице.
 * 
 * Для связи с процессом используется processId + timestamp,
 * что позволяет делать эффективный JOIN в Druid.
 * 
 * @property processId Идентификатор процесса-владельца переменной
 * @property timestamp Временная метка (синхронизирована с событием процесса)
 * @property varPath Полный путь к переменной (поддерживает вложенность)
 * @property varValue Значение переменной в строковом представлении
 * @property varType Тип данных переменной для правильной интерпретации
 */
data class ProcessVariableRecord(
    /** 
     * Идентификатор процесса-владельца.
     * Используется для JOIN с таблицей process_events.
     */
    val processId: String,
    
    /** 
     * Временная метка, синхронизированная с ProcessEventRecord.
     * Должна совпадать для корректного JOIN в запросах.
     */
    val timestamp: Instant,
    
    /** 
     * Полный путь к переменной в точечной нотации.
     * 
     * Примеры:
     * - "caseId" - переменная верхнего уровня
     * - "staticData.statusCode" - вложенное поле
     * - "epkData.addresses[0].city" - элемент массива (если поддерживается)
     * 
     * Путь позволяет восстановить иерархическую структуру переменных.
     */
    val varPath: String,
    
    /** 
     * Значение переменной, сериализованное в строку.
     * 
     * Правила сериализации зависят от типа:
     * - string: значение как есть
     * - number: строковое представление числа
     * - boolean: "true" или "false"
     * - date: ISO-8601 формат
     * - json: сериализованный JSON
     * - null: null (не строка "null")
     */
    val varValue: String?,
    
    /** 
     * Тип данных переменной.
     * Используется для правильной десериализации и типизации в запросах.
     * @see TYPE_STRING, TYPE_NUMBER, TYPE_BOOLEAN, TYPE_DATE, TYPE_JSON, TYPE_NULL
     */
    val varType: String
) {
    /**
     * Преобразует запись в Map для отправки в Druid.
     * 
     * @return Map с данными для Druid ingestion
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "process_id" to processId,
        "__time" to timestamp.toEpochMilli(),
        "var_path" to varPath,
        "var_value" to varValue,
        "var_type" to varType
    )
    
    companion object {
        /** Название источника данных для переменных процессов */
        const val DATA_SOURCE_NAME = DruidDataSources.Eav.VARIABLES
        
        // ========================================
        // КОНСТАНТЫ ТИПОВ ДАННЫХ
        // ========================================
        
        /** Строковое значение */
        const val TYPE_STRING = "string"
        
        /** Числовое значение (целое или с плавающей точкой) */
        const val TYPE_NUMBER = "number"
        
        /** Логическое значение (true/false) */
        const val TYPE_BOOLEAN = "boolean"
        
        /** Дата/время в формате ISO-8601 */
        const val TYPE_DATE = "date"
        
        /** Сложный объект или массив в JSON-формате */
        const val TYPE_JSON = "json"
        
        /** Отсутствующее значение (null) */
        const val TYPE_NULL = "null"
    }
}

/**
 * Запечатанный класс для представления EAV-записей.
 * 
 * Объединяет события процессов и их переменные под общим интерфейсом.
 * Используется для унификации обработки в парсере и при отправке в Druid.
 * 
 * Sealed class гарантирует, что все возможные типы записей
 * обрабатываются явно в when-выражениях, предотвращая ошибки.
 */
sealed class EavRecord {
    /** 
     * Преобразует запись в Map для отправки в Druid.
     * Каждый подкласс реализует свою логику сериализации.
     */
    abstract fun toMap(): Map<String, Any?>
    
    /** 
     * Название целевого источника данных в Druid.
     * Определяет, в какую таблицу будет записана эта запись.
     */
    abstract val dataSourceName: String
    
    /**
     * Обёртка для записи события процесса.
     * 
     * Содержит метаданные процесса и направляется в datasource "process_events".
     * Одна запись Event соответствует одному состоянию процесса.
     */
    data class Event(val record: ProcessEventRecord) : EavRecord() {
        override fun toMap() = record.toMap()
        override val dataSourceName = ProcessEventRecord.DATA_SOURCE_NAME
    }
    
    /**
     * Обёртка для записи переменной процесса.
     * 
     * Направляется в datasource "process_variables".
     * Один процесс может порождать множество Variable записей
     * (по одной на каждую переменную).
     */
    data class Variable(val record: ProcessVariableRecord) : EavRecord() {
        override fun toMap() = record.toMap()
        override val dataSourceName = ProcessVariableRecord.DATA_SOURCE_NAME
    }
}
