package ru.sber.parser.model.druid

import java.time.Instant

/**
 * Гибридная модель данных для Apache Druid.
 * 
 * Эта модель объединяет преимущества широкой (wide) и JSON-схемы:
 * - Часто используемые поля хранятся как отдельные колонки (быстрый доступ)
 * - Редко используемые данные группируются в JSON-блобы по категориям
 * 
 * Структура данных:
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                         ГИБРИДНАЯ ЗАПИСЬ                                │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │  Базовые поля процесса (всегда присутствуют)                           │
 * │  ├── processId, timestamp, processName, state, ...                     │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │  Горячие переменные (отдельные колонки)                                │
 * │  ├── varCaseId, varEpkId, varFio, varStatus, ...                       │
 * │  ├── varStaticDataCaseId, varStaticDataStatusCode, ...                 │
 * │  └── varEpkDataUcpId, varEpkDataClientStatus, ...                      │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │  JSON-блобы по категориям (сгруппированные данные)                     │
 * │  ├── varEpkDataJson    - полный объект данных ЕПК                      │
 * │  ├── varStaticDataJson - полный объект статических данных              │
 * │  ├── varAnswerGFLJson  - ответы ГФЛ                                    │
 * │  └── varOtherJson      - прочие переменные                             │
 * └─────────────────────────────────────────────────────────────────────────┘
 * 
 * Преимущества гибридного подхода:
 * - Один источник данных (datasource) = нет JOIN
 * - Быстрые запросы по горячим полям (индексированные колонки)
 * - Полные данные доступны в JSON для детального анализа
 * - Группировка JSON по категориям упрощает частичное извлечение
 * 
 * Когда использовать:
 * - Нужен баланс между производительностью и полнотой данных
 * - Частые запросы по известному набору полей
 * - Периодическая необходимость в доступе к полным данным
 * 
 * @see CombinedRecord для альтернативного двухтабличного подхода
 * @see EavRecord для классического EAV-подхода
 */
data class HybridRecord(
    // ========================================
    // ПЕРВИЧНЫЙ КЛЮЧ И ВРЕМЕННАЯ МЕТКА
    // ========================================
    
    /** 
     * Уникальный идентификатор экземпляра процесса.
     * Является первичным ключом записи.
     */
    val processId: String,
    
    /** 
     * Временная метка события.
     * Используется Druid для:
     * - Партиционирования данных по времени (сегменты)
     * - Колонки __time для time-series запросов
     * - Эффективной фильтрации по временным диапазонам
     */
    val timestamp: Instant,
    
    // ========================================
    // БАЗОВЫЕ ПОЛЯ ПРОЦЕССА
    // Эти поля присутствуют в каждом сообщении Kafka
    // ========================================
    
    /** 
     * Название бизнес-процесса (определение BPMN).
     * Пример: "processClientRequest", "handlePayment"
     */
    val processName: String,
    
    /** 
     * Числовой код состояния процесса:
     * - 0: PENDING - ожидает запуска
     * - 1: ACTIVE - выполняется
     * - 2: COMPLETED - успешно завершён
     * - 3: ABORTED - прерван с ошибкой
     * - 4: SUSPENDED - приостановлен
     */
    val state: Int,
    
    /** Идентификатор модуля/подсистемы, владеющей процессом */
    val moduleId: String?,
    
    /** Бизнес-ключ для связи с внешними системами (заявка, договор) */
    val businessKey: String?,
    
    /** ID корневого процесса (для подпроцессов) */
    val rootInstanceId: String?,
    
    /** ID родительского процесса (для подпроцессов) */
    val parentInstanceId: String?,
    
    /** Версия BPMN-диаграммы процесса */
    val version: Int?,
    
    /** Дата завершения (null если активен) */
    val endDate: Instant?,
    
    /** Сообщение об ошибке при аварийном завершении */
    val error: String?,
    
    // ========================================
    // ГОРЯЧИЕ ПЕРЕМЕННЫЕ ВЕРХНЕГО УРОВНЯ
    // Наиболее часто запрашиваемые поля
    // ========================================
    
    /** Идентификатор обращения клиента */
    val varCaseId: String?,
    
    /** Идентификатор клиента в системе ЕПК */
    val varEpkId: String?,
    
    /** ФИО клиента */
    val varFio: String?,
    
    /** Идентификатор клиента в УЦП */
    val varUcpId: String?,
    
    /** Текущий статус обработки обращения */
    val varStatus: String?,
    
    /** Глобальный идентификатор экземпляра */
    val varGlobalInstanceId: String?,
    
    /** ID взаимодействия с клиентом */
    val varInteractionId: String?,
    
    /** Дата взаимодействия */
    val varInteractionDate: String?,
    
    /** Тема обращения */
    val varTheme: String?,
    
    /** Результат обработки */
    val varResult: String?,
    
    // ========================================
    // ГОРЯЧИЕ ПОЛЯ STATIC DATA
    // Извлечённые из объекта staticData
    // ========================================
    
    /** ID кейса из статических данных */
    val varStaticDataCaseId: String?,
    
    /** EPK ID клиента (числовой) */
    val varStaticDataClientEpkId: Long?,
    
    /** Публичный ID кейса */
    val varStaticDataCasePublicId: String?,
    
    /** Код статуса обращения */
    val varStaticDataStatusCode: String?,
    
    /** Время регистрации обращения */
    val varStaticDataRegistrationTime: Instant?,
    
    /** Время закрытия обращения */
    val varStaticDataClosedTime: Instant?,
    
    /** Версия классификатора тематик */
    val varStaticDataClassifierVersion: Int?,
    
    // ========================================
    // ГОРЯЧИЕ ПОЛЯ EPK DATA
    // Извлечённые из объекта epkData
    // ========================================
    
    /** UCP ID из данных ЕПК */
    val varEpkDataUcpId: String?,
    
    /** Числовой статус клиента в ЕПК */
    val varEpkDataClientStatus: Int?,
    
    /** Пол клиента (1=муж, 2=жен) */
    val varEpkDataGender: Int?,
    
    // ========================================
    // ЗАГОЛОВКИ ТРАССИРОВКИ
    // Для отладки и мониторинга запросов
    // ========================================
    
    /** Request ID для трассировки */
    val varTracingHeadersRequestId: String?,
    
    /** Trace ID для распределённой трассировки */
    val varTracingHeadersTraceId: String?,
    
    // ========================================
    // JSON-БЛОБЫ ПО КАТЕГОРИЯМ
    // Полные данные, сгруппированные по типу
    // ========================================
    
    /** 
     * JSON-массив узлов (шагов) процесса.
     * Содержит полную историю выполнения: шаги, тайминги, переходы.
     * Всегда присутствует, даже если пустой массив "[]".
     */
    val nodeInstancesJson: String,
    
    /** 
     * Полный объект epkData в JSON-формате.
     * Содержит все данные клиента из ЕПК, включая:
     * - Контактные данные
     * - Адреса
     * - Документы
     * - Статусы и флаги
     */
    val varEpkDataJson: String?,
    
    /** 
     * Полный объект staticData в JSON-формате.
     * Содержит статические данные обращения:
     * - Классификация
     * - Ответственные
     * - Сроки и SLA
     * - История изменений
     */
    val varStaticDataJson: String?,
    
    /** 
     * Ответы системы ГФЛ (Голосовые Функции Лоялти).
     * Содержит результаты автоматической классификации
     * и рекомендации по обработке обращения.
     */
    val varAnswerGFLJson: String?,
    
    /** 
     * Прочие переменные, не вошедшие в другие категории.
     * Служит "корзиной" для редко используемых данных.
     * Структура: {"varName1": value1, "varName2": value2, ...}
     */
    val varOtherJson: String?
) {
    /**
     * Преобразует гибридную запись в Map для отправки в Druid.
     * 
     * Маппинг полей:
     * - __time: специальная колонка Druid (в миллисекундах)
     * - Instant поля конвертируются в epoch milliseconds
     * - null значения сохраняются для корректной обработки
     * - Имена колонок используют snake_case для Druid
     * 
     * @return Map, готовый для сериализации и отправки в Druid
     */
    fun toMap(): Map<String, Any?> = mapOf(
        // Идентификаторы
        "process_id" to processId,
        "__time" to timestamp.toEpochMilli(),
        
        // Базовые поля процесса
        "process_name" to processName,
        "state" to state,
        "module_id" to moduleId,
        "business_key" to businessKey,
        "root_instance_id" to rootInstanceId,
        "parent_instance_id" to parentInstanceId,
        "version" to version,
        "end_date" to endDate?.toEpochMilli(),
        "error" to error,
        
        // Горячие переменные верхнего уровня
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
        
        // Горячие поля staticData
        "var_staticData_caseId" to varStaticDataCaseId,
        "var_staticData_clientEpkId" to varStaticDataClientEpkId,
        "var_staticData_casePublicId" to varStaticDataCasePublicId,
        "var_staticData_statusCode" to varStaticDataStatusCode,
        "var_staticData_registrationTime" to varStaticDataRegistrationTime?.toEpochMilli(),
        "var_staticData_closedTime" to varStaticDataClosedTime?.toEpochMilli(),
        "var_staticData_classifierVersion" to varStaticDataClassifierVersion,
        
        // Горячие поля epkData
        "var_epkData_ucpId" to varEpkDataUcpId,
        "var_epkData_clientStatus" to varEpkDataClientStatus,
        "var_epkData_gender" to varEpkDataGender,
        
        // Заголовки трассировки
        "var_tracingHeaders_requestId" to varTracingHeadersRequestId,
        "var_tracingHeaders_traceId" to varTracingHeadersTraceId,
        
        // JSON-блобы
        "node_instances_json" to nodeInstancesJson,
        "var_epkData_json" to varEpkDataJson,
        "var_staticData_json" to varStaticDataJson,
        "var_answerGFL_json" to varAnswerGFLJson,
        "var_other_json" to varOtherJson
    )
    
    companion object {
        /** 
         * Название источника данных в Apache Druid.
         * Все гибридные записи направляются в этот единственный datasource.
         */
        const val DATA_SOURCE_NAME = "process_hybrid"
    }
}
