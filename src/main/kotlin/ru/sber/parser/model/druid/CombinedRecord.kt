package ru.sber.parser.model.druid

import ru.sber.parser.druid.DruidDataSources
import java.time.Instant

/**
 * Комбинированная модель данных для Apache Druid.
 * 
 * Эта модель реализует подход "Два источника данных" (Two-DataSource):
 * 1. Основная таблица (process_main) - содержит все данные процесса в одной записи
 * 2. Индексная таблица (process_variables_indexed) - для поиска по редким переменным
 * 
 * Преимущества подхода:
 * - Оптимальный баланс между производительностью запросов и объёмом хранения
 * - "Горячие" переменные доступны напрямую без JOIN
 * - "Холодные" переменные хранятся в JSON-блобах и индексируются отдельно
 * - Поддержка поиска по любым переменным через индексную таблицу
 */

/**
 * Основная запись процесса для источника данных Druid.
 * 
 * Структура записи разделена на уровни (tiers) по частоте обращения:
 * - Tier 1 (горячие данные): часто используемые переменные как отдельные колонки
 * - Tier 3 (холодные данные): редко используемые данные в JSON-блобах
 * 
 * @property processId Уникальный идентификатор экземпляра процесса (первичный ключ)
 * @property timestamp Временная метка события процесса (используется Druid для партиционирования)
 */
data class ProcessMainRecord(
    // ========================================
    // ПЕРВИЧНЫЙ КЛЮЧ И ВРЕМЕННАЯ МЕТКА
    // ========================================
    
    /** Уникальный идентификатор экземпляра процесса */
    val processId: String,
    
    /** 
     * Временная метка создания/обновления процесса.
     * Используется Druid как __time колонка для сегментации данных по времени.
     */
    val timestamp: Instant,
    
    // ========================================
    // БАЗОВЫЕ ПОЛЯ ПРОЦЕССА
    // ========================================
    
    /** Название бизнес-процесса (например, "processClientRequest") */
    val processName: String,
    
    /** 
     * Состояние процесса:
     * - 0: PENDING (ожидание)
     * - 1: ACTIVE (активен)
     * - 2: COMPLETED (завершён)
     * - 3: ABORTED (прерван)
     * - 4: SUSPENDED (приостановлен)
     */
    val state: Int,
    
    /** Идентификатор модуля, которому принадлежит процесс */
    val moduleId: String?,
    
    /** Бизнес-ключ для связи с внешними системами */
    val businessKey: String?,
    
    /** Идентификатор корневого процесса (для подпроцессов) */
    val rootInstanceId: String?,
    
    /** Идентификатор родительского процесса (для подпроцессов) */
    val parentInstanceId: String?,
    
    /** Версия определения процесса */
    val version: Int?,
    
    /** Дата завершения процесса (null если процесс ещё активен) */
    val endDate: Instant?,
    
    /** Текст ошибки, если процесс завершился с ошибкой */
    val error: String?,
    
    // ========================================
    // TIER 1: ГОРЯЧИЕ ПЕРЕМЕННЫЕ (HOT VARIABLES)
    // Часто используемые переменные как отдельные колонки
    // для быстрого доступа и фильтрации в SQL запросах
    // ========================================
    
    /** Идентификатор обращения клиента */
    val varCaseId: String?,
    
    /** Идентификатор клиента в ЕПК (Единый Профиль Клиента) */
    val varEpkId: String?,
    
    /** ФИО клиента */
    val varFio: String?,
    
    /** Идентификатор клиента в УЦП (Универсальная Цифровая Платформа) */
    val varUcpId: String?,
    
    /** Текущий статус обработки */
    val varStatus: String?,
    
    /** Глобальный идентификатор экземпляра процесса */
    val varGlobalInstanceId: String?,
    
    /** Идентификатор взаимодействия с клиентом */
    val varInteractionId: String?,
    
    /** Дата взаимодействия с клиентом */
    val varInteractionDate: String?,
    
    /** Тема обращения */
    val varTheme: String?,
    
    /** Результат обработки */
    val varResult: String?,
    
    // ========================================
    // ГОРЯЧИЕ ПОЛЯ СТАТИЧЕСКИХ ДАННЫХ (STATIC DATA)
    // Часто запрашиваемые поля из объекта staticData
    // ========================================
    
    /** Идентификатор кейса из статических данных */
    val varStaticDataCaseId: String?,
    
    /** EPK ID клиента из статических данных (числовой формат) */
    val varStaticDataClientEpkId: Long?,
    
    /** Публичный идентификатор кейса */
    val varStaticDataCasePublicId: String?,
    
    /** Код статуса из статических данных */
    val varStaticDataStatusCode: String?,
    
    /** Время регистрации обращения */
    val varStaticDataRegistrationTime: Instant?,
    
    /** Время закрытия обращения */
    val varStaticDataClosedTime: Instant?,
    
    /** Версия классификатора */
    val varStaticDataClassifierVersion: Int?,
    
    // ========================================
    // ГОРЯЧИЕ ПОЛЯ ДАННЫХ ЕПК (EPK DATA)
    // Часто запрашиваемые поля из объекта epkData
    // ========================================
    
    /** UCP ID из данных ЕПК */
    val varEpkDataUcpId: String?,
    
    /** Статус клиента в ЕПК (числовой код) */
    val varEpkDataClientStatus: Int?,
    
    /** Пол клиента: 1 - мужской, 2 - женский */
    val varEpkDataGender: Int?,
    
    // ========================================
    // ЗАГОЛОВКИ ТРАССИРОВКИ (TRACING HEADERS)
    // Для отладки и мониторинга
    // ========================================
    
    /** Идентификатор запроса для трассировки */
    val varTracingHeadersRequestId: String?,
    
    /** Trace ID для распределённой трассировки */
    val varTracingHeadersTraceId: String?,
    
    // ========================================
    // TIER 3: ХОЛОДНЫЕ JSON-БЛОБЫ (COLD JSON BLOBS)
    // Редко используемые данные в сериализованном виде
    // ========================================
    
    /** 
     * JSON-массив всех узлов (node instances) процесса.
     * Содержит информацию о выполненных шагах, таймингах, переходах.
     * Формат: [{"nodeId": "...", "nodeName": "...", "startDate": ..., "endDate": ...}, ...]
     */
    val nodeInstancesJson: String,
    
    /** 
     * JSON-блоб со всеми "холодными" переменными процесса.
     * Содержит переменные, которые не вошли в горячие поля.
     * Используется для полного восстановления состояния процесса.
     */
    val varBlobJson: String?
) {
    /**
     * Преобразует запись в Map для отправки в Druid.
     * 
     * Ключи соответствуют названиям колонок в Druid:
     * - __time: специальная колонка Druid для временной метки
     * - Все timestamp конвертируются в миллисекунды (epoch millis)
     * - null значения сохраняются для правильной обработки в Druid
     * 
     * @return Map с данными для Druid ingestion
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "process_id" to processId,
        "__time" to timestamp.toEpochMilli(),
        "process_name" to processName,
        "state" to state,
        "module_id" to moduleId,
        "business_key" to businessKey,
        "root_instance_id" to rootInstanceId,
        "parent_instance_id" to parentInstanceId,
        "version" to version,
        "end_date" to endDate?.toEpochMilli(),
        "error" to error,
        "var_caseId" to varCaseId,
        "var_epkId" to varEpkId,
        "var_fio" to varFio,
        "var_ucpId" to varUcpId,
        "var_status" to varStatus,
        "var_globalInstanceId" to varGlobalInstanceId,
        "var_interactionId" to varInteractionId,
        "var_interactionDate" to varInteractionDate,
        "var_theme" to varTheme,
        "var_result" to varResult,
        "var_staticData_caseId" to varStaticDataCaseId,
        "var_staticData_clientEpkId" to varStaticDataClientEpkId,
        "var_staticData_casePublicId" to varStaticDataCasePublicId,
        "var_staticData_statusCode" to varStaticDataStatusCode,
        "var_staticData_registrationTime" to varStaticDataRegistrationTime?.toEpochMilli(),
        "var_staticData_closedTime" to varStaticDataClosedTime?.toEpochMilli(),
        "var_staticData_classifierVersion" to varStaticDataClassifierVersion,
        "var_epkData_ucpId" to varEpkDataUcpId,
        "var_epkData_clientStatus" to varEpkDataClientStatus,
        "var_epkData_gender" to varEpkDataGender,
        "var_tracingHeaders_requestId" to varTracingHeadersRequestId,
        "var_tracingHeaders_traceId" to varTracingHeadersTraceId,
        "node_instances_json" to nodeInstancesJson,
        "var_blob_json" to varBlobJson
    )
    
    companion object {
        /** Название источника данных (datasource) в Apache Druid */
        const val DATA_SOURCE_NAME = DruidDataSources.Combined.MAIN
    }
}

/**
 * Запись индексированной переменной процесса.
 * 
 * Используется для создания вторичного индекса по переменным процесса.
 * Позволяет выполнять поиск процессов по значениям любых переменных,
 * включая вложенные поля в JSON-объектах.
 * 
 * Пример использования:
 * - Найти все процессы, где staticData.statusCode = "CLOSED"
 * - Найти процессы по определённому значению epkData.clientStatus
 * 
 * @property processId Идентификатор процесса (для JOIN с основной таблицей)
 * @property timestamp Временная метка (для партиционирования в Druid)
 * @property varCategory Категория переменной (например, "staticData", "epkData", "root")
 * @property varPath Полный путь к переменной (например, "staticData.statusCode")
 * @property varValue Значение переменной в строковом представлении
 * @property varType Тип данных переменной (string, number, boolean, date, json, null)
 */
data class ProcessVariableIndexedRecord(
    /** Идентификатор процесса для связи с основной таблицей */
    val processId: String,
    
    /** Временная метка для партиционирования */
    val timestamp: Instant,
    
    /** 
     * Категория переменной:
     * - "root": переменные верхнего уровня (caseId, epkId, fio и т.д.)
     * - "staticData": вложенные поля объекта staticData
     * - "epkData": вложенные поля объекта epkData
     * - "tracingHeaders": заголовки трассировки
     * - "answerGFL": ответы ГФЛ
     * - "other": прочие переменные
     */
    val varCategory: String,
    
    /** 
     * Полный путь к переменной в точечной нотации.
     * Примеры: "caseId", "staticData.statusCode", "epkData.client.gender"
     */
    val varPath: String,
    
    /** 
     * Значение переменной, преобразованное в строку.
     * Для сложных объектов содержит JSON-представление.
     */
    val varValue: String?,
    
    /** 
     * Тип данных переменной:
     * - "string": строковое значение
     * - "number": числовое значение (целое или с плавающей точкой)
     * - "boolean": логическое значение (true/false)
     * - "date": дата/время в формате ISO-8601
     * - "json": сложный объект или массив
     * - "null": отсутствующее значение
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
        "var_category" to varCategory,
        "var_path" to varPath,
        "var_value" to varValue,
        "var_type" to varType
    )
    
    companion object {
        /** Название источника данных для индексированных переменных */
        const val DATA_SOURCE_NAME = DruidDataSources.Combined.VARIABLES_INDEXED
    }
}

/**
 * Запечатанный класс для представления комбинированных записей.
 * 
 * Используется для унификации работы с разными типами записей
 * в парсере и отправщике данных в Druid.
 * 
 * Паттерн Sealed Class позволяет:
 * - Гарантировать обработку всех типов записей в when-выражениях
 * - Обеспечить типобезопасность при маршрутизации записей
 * - Упростить добавление новых типов записей в будущем
 */
sealed class CombinedRecord {
    /** Преобразует запись в Map для отправки в Druid */
    abstract fun toMap(): Map<String, Any?>
    
    /** Название целевого источника данных (datasource) в Druid */
    abstract val dataSourceName: String
    
    /**
     * Обёртка для основной записи процесса.
     * Направляется в datasource "process_main".
     */
    data class Main(val record: ProcessMainRecord) : CombinedRecord() {
        override fun toMap() = record.toMap()
        override val dataSourceName = ProcessMainRecord.DATA_SOURCE_NAME
    }
    
    /**
     * Обёртка для записи индексированной переменной.
     * Направляется в datasource "process_variables_indexed".
     */
    data class Variable(val record: ProcessVariableIndexedRecord) : CombinedRecord() {
        override fun toMap() = record.toMap()
        override val dataSourceName = ProcessVariableIndexedRecord.DATA_SOURCE_NAME
    }
}
