package ru.sber.parser.config

/**
 * Классификация полей BPM-сообщений по уровням доступа (tiers).
 * 
 * Данный класс определяет, какие поля из переменных процесса будут:
 * - Tier 1 (горячие данные): храниться как отдельные колонки для быстрого поиска
 * - Tier 2 (тёплые данные): индексироваться в отдельной таблице для гибкого поиска
 * - Tier 3 (холодные данные): храниться в JSON-блобах для полноты данных
 * 
 * Классификация используется стратегиями HybridStrategy и CombinedStrategy
 * для определения структуры выходных данных.
 * 
 * Принцип распределения:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Tier 1 (Hot)  │ Часто запрашиваемые поля → отдельные колонки│
 * │ Tier 2 (Warm) │ Поиск по категориям → индексная таблица     │
 * │ Tier 3 (Cold) │ Редко нужные данные → JSON-блобы            │
 * └─────────────────────────────────────────────────────────────┘
 * 
 * @property tier1HotColumns Список маппингов горячих полей (путь → колонка)
 * @property tier2WarmCategories Категории тёплых данных с путями полей
 * @property tier3ColdBlobs Список путей к холодным данным для JSON-сериализации
 * 
 * @see FieldMapping маппинг пути к колонке
 * @see FieldType поддерживаемые типы данных
 */
data class FieldClassification(
    val tier1HotColumns: List<FieldMapping>,
    val tier2WarmCategories: Map<String, List<String>>,
    val tier3ColdBlobs: List<String>
) {
    companion object {
        /**
         * Создаёт конфигурацию по умолчанию с типичными полями BPM-процессов.
         * 
         * Tier 1 (горячие колонки):
         * - caseId, epkId, fio, ucpId, status — основные идентификаторы
         * - staticData.* — метаданные обращения
         * - epkData.epkEntity.* — данные клиента
         * - tracingHeaders.* — заголовки трассировки
         * 
         * Tier 2 (тёплые категории):
         * - epkData — полные данные клиента из ЕПК
         * - staticData — все метаданные обращения
         * - tracingHeaders — заголовки для отладки
         * - startAttributes — атрибуты запуска процесса
         * 
         * Tier 3 (холодные JSON-блобы):
         * - answerGFL — ответы системы ГФЛ
         * - opHistory — история операций
         * - gflData — данные ГФЛ
         * - ai_result — результаты AI-обработки
         * 
         * @return Конфигурация классификации полей по умолчанию
         */
        fun default(): FieldClassification = FieldClassification(
            tier1HotColumns = listOf(
                FieldMapping("caseId", "var_caseId", FieldType.STRING),
                FieldMapping("epkId", "var_epkId", FieldType.STRING),
                FieldMapping("fio", "var_fio", FieldType.STRING),
                FieldMapping("ucpId", "var_ucpId", FieldType.STRING),
                FieldMapping("status", "var_status", FieldType.STRING),
                FieldMapping("globalInstanceId", "var_globalInstanceId", FieldType.STRING),
                FieldMapping("INTERACTION_ID", "var_interactionId", FieldType.STRING),
                FieldMapping("INTERACTION_DATE", "var_interactionDate", FieldType.STRING),
                FieldMapping("theme", "var_theme", FieldType.STRING),
                FieldMapping("result", "var_result", FieldType.STRING),
                FieldMapping("staticData.caseId", "var_staticData_caseId", FieldType.STRING),
                FieldMapping("staticData.clientEpkId", "var_staticData_clientEpkId", FieldType.LONG),
                FieldMapping("staticData.casePublicId", "var_staticData_casePublicId", FieldType.STRING),
                FieldMapping("staticData.statusCode", "var_staticData_statusCode", FieldType.STRING),
                FieldMapping("staticData.registrationTime", "var_staticData_registrationTime", FieldType.TIMESTAMP),
                FieldMapping("staticData.closedTime", "var_staticData_closedTime", FieldType.TIMESTAMP),
                FieldMapping("staticData.classifierVersion", "var_staticData_classifierVersion", FieldType.INT),
                FieldMapping("epkData.epkEntity.ucpId", "var_epkData_ucpId", FieldType.STRING),
                FieldMapping("epkData.epkEntity.clientStatus", "var_epkData_clientStatus", FieldType.INT),
                FieldMapping("epkData.epkEntity.gender", "var_epkData_gender", FieldType.INT),
                FieldMapping("tracingHeaders.x-request-id", "var_tracingHeaders_requestId", FieldType.STRING),
                FieldMapping("tracingHeaders.x-b3-traceid", "var_tracingHeaders_traceId", FieldType.STRING)
            ),
            tier2WarmCategories = mapOf(
                "epkData" to listOf(
                    "epkEntity.ucpId",
                    "epkEntity.version",
                    "epkEntity.clientStatus",
                    "epkEntity.gender",
                    "epkEntity.birthDate",
                    "epkEntity.deathDate",
                    "epkEntity.names[*].surname",
                    "epkEntity.names[*].name",
                    "epkEntity.names[*].patronymic",
                    "epkEntity.phoneNumbers[*].phoneNumber",
                    "epkEntity.phoneNumbers[*].contactStatus",
                    "epkEntity.phoneNumbers[*].usageType",
                    "epkEntity.phoneNumbers[*].region",
                    "epkEntity.identifications[*].documentSeries",
                    "epkEntity.identifications[*].documentNumber",
                    "epkEntity.identifications[*].documentTypeCode",
                    "customerKnowledge[*].value",
                    "customerKnowledge[*].id"
                ),
                "staticData" to listOf(
                    "classifierVersion",
                    "caseId",
                    "classifierMasterId",
                    "classifierParentMasterId",
                    "casePublicId",
                    "clientEpkId",
                    "statusCode",
                    "statusName",
                    "crmAppealNum",
                    "considerationResultCode",
                    "considerationResultName",
                    "crmRequestId",
                    "lastUpdateTime",
                    "closedTime",
                    "registrationTime",
                    "publicDescription",
                    "caseEcmFolder",
                    "pprbCase",
                    "contactSettingsStart",
                    "contactSettingsFinish",
                    "contactSettingsTimeZone",
                    "expirationTime",
                    "initialExpirationTime",
                    "initialInteractionId"
                ),
                "tracingHeaders" to listOf(
                    "x-request-id",
                    "x-b3-parentspanid",
                    "x-b3-spanid",
                    "x-b3-traceid",
                    "x-b3-sampled"
                ),
                "startAttributes" to listOf(
                    "caseId",
                    "attributes[*].inputSumm",
                    "attributes[*].inputCC.DPAN",
                    "attributes[*].inputCC.endDate",
                    "attributes[*].inputCC.accountNum",
                    "attributes[*].inputDC.DPAN",
                    "attributes[*].inputDC.endDate",
                    "attributes[*].inputDC.accountNum"
                ),
                "inputCC" to listOf(
                    "DPAN",
                    "endDate",
                    "accountNum",
                    "processing"
                ),
                "inputDC" to listOf(
                    "DPAN",
                    "endDate",
                    "accountNum",
                    "processing"
                )
            ),
            tier3ColdBlobs = listOf(
                "answerGFL",
                "opHistory",
                "gflData",
                "ai_result"
            )
        )
    }
}

/**
 * Маппинг пути переменной на колонку Druid.
 * 
 * Определяет, как переменная из BPM-сообщения будет преобразована
 * в колонку таблицы Apache Druid.
 * 
 * Примеры маппингов:
 * - "caseId" → "var_caseId" (простое поле верхнего уровня)
 * - "staticData.clientEpkId" → "var_staticData_clientEpkId" (вложенное поле)
 * - "epkData.epkEntity.ucpId" → "var_epkData_ucpId" (глубоко вложенное)
 * 
 * @property sourcePath Путь к полю в JSON (через точку для вложенных)
 * @property targetColumn Имя колонки в Druid (snake_case с префиксом var_)
 * @property type Тип данных для корректной сериализации
 */
data class FieldMapping(
    val sourcePath: String,
    val targetColumn: String,
    val type: FieldType
)

/**
 * Типы данных для полей в Apache Druid.
 * 
 * Используется для корректного определения типа колонки
 * при создании спецификации ingestion и при сериализации значений.
 * 
 * Соответствие типов Kotlin → Druid:
 * - STRING → VARCHAR
 * - INT, LONG → BIGINT
 * - DOUBLE → DOUBLE
 * - BOOLEAN → VARCHAR (строка "true"/"false")
 * - TIMESTAMP → TIMESTAMP (epoch millis или ISO-8601)
 * - JSON → VARCHAR (сериализованный JSON)
 */
enum class FieldType {
    /** Строковое значение (VARCHAR в Druid) */
    STRING,
    
    /** Целое число 32-бит (BIGINT в Druid) */
    INT,
    
    /** Целое число 64-бит (BIGINT в Druid) */
    LONG,
    
    /** Число с плавающей точкой (DOUBLE в Druid) */
    DOUBLE,
    
    /** Булево значение, хранится как строка */
    BOOLEAN,
    
    /** Временная метка (epoch millis для __time) */
    TIMESTAMP,
    
    /** JSON-объект, сериализованный в строку */
    JSON
}
