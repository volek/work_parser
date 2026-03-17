# BPM Message Parser для Apache Druid

## Обзор проекта

Реализация Kotlin-парсера BPM-сообщений для Apache Druid с поддержкой пяти стратегий хранения: гибридной (Flat+JSON), вертикальной (EAV), комбинированной (Combined), компактной комбинированной (Compcom, без cold blob) и Default (все поля как колонки). Включает генератор тестовых сообщений, парсеры, клиент Druid и 250+ тестовых запросов.

## Статус задач

| ID | Задача | Статус |
|----|--------|--------|
| init-project | Создать структуру Gradle-проекта с Kotlin DSL и зависимостями (Jackson, Ktor, Coroutines) | ✅ Completed |
| domain-models | Реализовать domain-модели: BpmMessage, ProcessInstance, NodeInstance, Variables | ✅ Completed |
| message-generator | Создать генератор 25+ тестовых сообщений на основе samples с вариативностью данных | ✅ Completed |
| hybrid-strategy | Реализовать парсер Вариант 1 (Hybrid): flat columns + JSON blobs | ✅ Completed |
| eav-strategy | Реализовать парсер Вариант 2 (EAV): process_events + process_variables с полным flatten | ✅ Completed |
| combined-strategy | Реализовать парсер Вариант 3 (Combined): Tier 1/2/3 классификация с конфигурируемыми полями | ✅ Completed |
| druid-client | Создать Druid HTTP-клиент с поддержкой ingestion и SQL-запросов | ✅ Completed |
| queries-hybrid | Написать 50+ SQL-запросов для Hybrid-стратегии (query/hybrid/) | ✅ Completed |
| queries-eav | Написать 50+ SQL-запросов для EAV-стратегии (query/eav/) | ✅ Completed |
| queries-combined | Написать 50+ SQL-запросов для Combined-стратегии (query/combined/) | ✅ Completed |
| tests | Написать unit-тесты для парсеров и интеграционные тесты для Druid-клиента | ✅ Completed |

---

## Архитектура проекта

```
c:\Projects\Sber\parser\
├── build.gradle.kts                 # Конфигурация Gradle с Kotlin DSL
├── settings.gradle.kts
├── gradle.properties
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── ru/sber/parser/
│   │           ├── Application.kt               # Entry point
│   │           ├── config/
│   │           │   ├── DruidConfig.kt           # Druid connection settings
│   │           │   └── FieldClassification.kt   # Tier 1/2/3 field config
│   │           ├── model/
│   │           │   ├── BpmMessage.kt            # Domain models
│   │           │   ├── ProcessInstance.kt
│   │           │   ├── NodeInstance.kt
│   │           │   └── druid/
│   │           │       ├── HybridRecord.kt      # Вариант 1: Flat+JSON
│   │           │       ├── EavRecord.kt         # Вариант 2: EAV
│   │           │       ├── CombinedRecord.kt    # Вариант 3: Combined
│   │           │       └── DefaultRecord.kt     # Default: все поля как колонки
│   │           ├── parser/
│   │           │   ├── MessageParser.kt         # JSON parsing interface
│   │           │   ├── VariableFlattener.kt     # Path extraction
│   │           │   └── strategy/
│   │           │       ├── ParseStrategy.kt     # Strategy interface
│   │           │       ├── HybridStrategy.kt    # Вариант 1
│   │           │       ├── EavStrategy.kt       # Вариант 2
│   │           │       ├── CombinedStrategy.kt  # Вариант 3 (warmVariablesLimit 10..1010)
│   │           │       ├── CompcomStrategy.kt   # Compact combined: без cold blob
│   │           │       └── DefaultStrategy.kt   # Default
│   │           ├── druid/
│   │           │   ├── DruidClient.kt           # HTTP client for Druid
│   │           │   ├── DruidIngester.kt         # Batch ingestion
│   │           │   └── SchemaGenerator.kt       # Dynamic schema
│   │           └── generator/
│   │               ├── MessageGenerator.kt      # Test message generator
│   │               └── templates/
│   │                   └── *.kt                 # Data templates
│   └── test/
│       └── kotlin/...
├── messages/                         # Generated test messages (20+)
├── query/
│   ├── hybrid/                       # 50+ queries for Hybrid
│   ├── eav/                          # 50+ queries for EAV
│   ├── combined/                     # 68 queries for Combined (process_main + process_variables_indexed)
│   ├── compcom/                      # 70 queries for Compcom (process_main_compact + process_variables_indexed)
│   └── default/                      # queries for Default
└── samples/                          # Existing sample files
```

---

## Фаза 1: Инициализация проекта и модели

### 1.1 Gradle-конфигурация

`build.gradle.kts` - основные зависимости:

```kotlin
dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("io.ktor:ktor-client-core:2.3.9")
    implementation("io.ktor:ktor-client-cio:2.3.9")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
```

### 1.2 Domain Models

Ключевые модели на основе анализа samples:

- `BpmMessage` - корневой объект сообщения
- `ProcessInstance` - id, processName, state, startDate, endDate, businessKey, moduleId, rootInstanceId, parentInstanceId
- `NodeInstance` - id, nodeName, nodeType, state, triggerTime, leaveTime, error
- `Variables` - динамический Map с вложенными объектами (epkData, staticData, tracingHeaders, answerGFL)

---

## Фаза 2: Генератор тестовых сообщений

### 2.1 Стратегия генерации

На основе 3 образцов из samples/ генерируем 25+ уникальных сообщений:

| Тип | Количество | Описание |
|-----|------------|----------|
| uvskRemainderReturn | 5 | На основе message2.json (полные данные) |
| uvskStupidsEarlyRehrenment | 5 | На основе message1.json (с answerGFL) |
| MassTransferProcessUnif | 5 | На основе message3.json (иерархия) |
| uvskFraudFin_front | 5 | Для query2 тестирования |
| tappeal_p2p_receiver | 5 | Для query3 тестирования |

### 2.2 Вариативность данных

Генератор создаёт вариации:

- Разные processName для охвата сценариев запросов
- Разные state (0, 1, 2, 4) для фильтрации
- Разные nodeType (startEvent, endEvent, restTask, subProcess, userTask)
- Разные временные диапазоны (последние 24 часа для query1-3)
- Разные значения epkId, caseId, fio для поиска

---

## Фаза 3: Реализация парсеров

### 3.1 Вариант 1: Гибридная схема (Flat + JSON)

**Таблица `process_hybrid`:**

```sql
-- HOT columns (индексируемые)
process_id          VARCHAR,    -- PK
process_name        VARCHAR,
start_date          TIMESTAMP,  -- __time
state               INT,
module_id           VARCHAR,
business_key        VARCHAR,
root_instance_id    VARCHAR,
parent_instance_id  VARCHAR,
-- Extracted variables (15-20 hot fields)
var_caseId          VARCHAR,
var_epkId           VARCHAR,
var_fio             VARCHAR,
var_ucpId           VARCHAR,
var_status          VARCHAR,
var_registrationTime TIMESTAMP,
var_clientEpkId     BIGINT,
var_casePublicId    VARCHAR,
var_globalInstanceId VARCHAR,
-- JSON blobs
node_instances_json  VARCHAR,   -- JSON array
var_epkData_json    VARCHAR,    -- Nested epkData
var_staticData_json VARCHAR,    -- Nested staticData
var_answerGFL_json  VARCHAR,    -- Large GFL response
var_other_json      VARCHAR     -- All other variables
```

**Логика парсера:**

1. Извлечь top-level поля процесса
2. Flatten 15-20 частых переменных в колонки
3. Сериализовать остальные в JSON-блобы

### 3.2 Вариант 2: Вертикальная модель (EAV)

**Таблица `process_events`:**

```sql
process_id          VARCHAR,    -- PK
process_name        VARCHAR,
start_date          TIMESTAMP,  -- __time
end_date            TIMESTAMP,
state               INT,
module_id           VARCHAR,
business_key        VARCHAR,
root_instance_id    VARCHAR,
node_instances_json VARCHAR     -- JSON для nodeInstances
```

**Таблица `process_variables`:**

```sql
process_id          VARCHAR,    -- FK
var_path            VARCHAR,    -- e.g. 'epkData.epkEntity.ucpId'
var_value           VARCHAR,    -- String value
var_type            VARCHAR,    -- 'string'|'number'|'boolean'|'date'|'json'
__time              TIMESTAMP   -- Same as process start_date
```

**Логика парсера:**

1. Записать основные данные в process_events
2. Рекурсивно flatten все variables в пары (path, value)
3. Для массивов создавать записи с индексом: `phoneNumbers[0].phoneNumber`

### 3.3 Вариант 3: Комбинированная архитектура

**Таблица `process_main`:** (как Hybrid, но с меньшим JSON)

**Таблица `process_variables_indexed`:** (EAV только для Tier 2)

```sql
process_id          VARCHAR,
var_category        VARCHAR,    -- 'epkData'|'staticData'|'tracingHeaders'
var_path            VARCHAR,    -- 'ucpId', 'phoneNumbers[0].phoneNumber'
var_value           VARCHAR,
var_type            VARCHAR,
__time              TIMESTAMP
```

**Конфигурация классификации:**

```kotlin
// Tier 1: HOT (колонки) - 20 полей
val tier1Fields = listOf(
    "caseId", "epkId", "fio", "status", "ucpId",
    "staticData.clientEpkId", "staticData.casePublicId",
    "staticData.registrationTime", "globalInstanceId"
)

// Tier 2: WARM (EAV) - структурированные вложенные
val tier2Categories = mapOf(
    "epkData" to listOf("epkEntity.ucpId", "epkEntity.names[*]", "epkEntity.phoneNumbers[*]"),
    "staticData" to listOf("classifierVersion", "statusCode", "closedTime"),
    "tracingHeaders" to listOf("x-request-id", "x-b3-traceid")
)

// Tier 3: COLD (JSON blob) - большие/редкие объекты
val tier3Blobs = listOf("answerGFL", "opHistory", "gflData")
```

---

## Фаза 4: Druid-клиент

### 4.1 HTTP-клиент для Druid

```kotlin
class DruidClient(private val config: DruidConfig) {
    suspend fun ingest(dataSource: String, records: List<Map<String, Any?>>)
    suspend fun query(sql: String): List<Map<String, Any?>>
    suspend fun createDataSource(spec: IngestionSpec)
}
```

### 4.2 Ingestion Spec Generator

Генерация JSON-спецификации для каждой стратегии:

- `dimensionsSpec` - колонки
- `metricsSpec` - агрегации
- `timestampSpec` - поле __time
- `tuningConfig` - партиционирование

---

## Фаза 5: Тестовые запросы (150+)

### 5.1 Категории запросов

| Категория | Описание | Кол-во |
|-----------|----------|--------|
| Basic SELECT | Простые выборки по колонкам | 10 |
| Filtering | WHERE по разным полям | 15 |
| Aggregations | COUNT, SUM, AVG, GROUP BY | 10 |
| Time-based | Фильтры по времени | 10 |
| JSON access | Доступ к вложенным данным | 15 |
| JOINs | Соединение таблиц (EAV/Combined) | 10 |
| Complex | Подзапросы, CASE, COALESCE | 10 |
| Performance | Запросы на большом объёме | 10 |

**Итого: 90 запросов x 3 стратегии = но многие общие, так что ~50 уникальных на стратегию**

### 5.2 Примеры запросов

**Адаптация query1.sql для каждой стратегии:**

**Hybrid:**

```sql
SELECT process_name, process_id, var_epkId,
       JSON_VALUE(node_instances_json, '$[0].nodeName') as nodeName
FROM process_hybrid
WHERE JSON_VALUE(node_instances_json, '$[0].nodeType') = 'startEvent'
  AND start_date >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE
```

**EAV:**

```sql
SELECT pe.process_name, pe.process_id, pv.var_value as epkId
FROM process_events pe
JOIN process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path = 'epkId'
  AND pe.start_date >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE
```

**Combined:**

```sql
SELECT pm.process_name, pm.process_id, pm.var_epkId
FROM process_main pm
WHERE pm.start_date >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE
```

---

## Структура файлов запросов

```
query/
├── hybrid/
│   ├── basic/
│   │   ├── q01_select_all.sql
│   │   ├── q02_select_by_process_name.sql
│   │   └── ...
│   ├── filtering/
│   │   ├── q10_filter_by_state.sql
│   │   └── ...
│   ├── json_access/
│   │   ├── q25_json_value_nested.sql
│   │   └── ...
│   └── complex/
│       └── ...
├── eav/
│   ├── basic/
│   ├── joins/
│   └── ...
└── combined/
    ├── basic/
    ├── tier_queries/
    └── ...
```

---

## Ключевые технические решения

1. **Jackson для парсинга JSON** - надёжный, поддержка Kotlin, datatype-jsr310 для дат
2. **Ktor Client для HTTP** - асинхронный, корутины, легковесный
3. **Strategy Pattern** - единый интерфейс ParseStrategy для всех вариантов
4. **Конфигурируемая классификация** - YAML/JSON файл для Tier 1/2/3 полей
5. **Batch Ingestion** - группировка записей для оптимальной производительности Druid

---

## Критические поля из анализа samples

На основе query1-query6 выявлены HOT-поля (Tier 1):

- `process.id`, `process.processName`, `process.state`
- `process.startDate`, `process.endDate`
- `process.rootInstanceId`, `process.processId`
- `process.variables.caseId`, `process.variables.epkId`
- `process.variables.staticData.clientEpkId`
- `process.variables.staticData.casePublicId`
- `process.variables.staticData.registrationTime`
- `process.variables.status`, `process.variables.answerType`
- `process.variables.processKeys.bbmoId`
- `node.nodeName`, `node.nodeType`, `node.state`
- `node.triggerTime`, `node.leaveTime`, `node.error`
