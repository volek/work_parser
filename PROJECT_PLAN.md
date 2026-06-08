# BPM Message Parser для Apache Druid

## Обзор проекта

Kotlin CLI-приложение для парсинга BPM-сообщений (JSON) и загрузки в Apache Druid. Поддерживает **пять стратегий хранения**: `hybrid`, `eav`, `combined`, `compcom`, `default`. Включает генератор тестовых сообщений, HTTP-клиент Druid (ingestion + SQL), пакетный прогон всех стратегий, **282 SQL-запроса** и **118 unit/integration-тестов**.

**Целевая платформа:** Linux host (bash + Java 17 + `./gradlew`). Docker/Compose и Windows PowerShell сценарии удалены. Основной runbook: `README.md`, `distribution/DEPLOYMENT.md`.

---

## Статус задач

| ID | Задача | Статус |
|----|--------|--------|
| init-project | Gradle-проект (Kotlin DSL), Jackson, Ktor, Coroutines, Hoplite, Logback | ✅ Completed |
| domain-models | Domain-модели: `BpmMessage`, `NodeInstance`, variables; расширенные BAM-поля | ✅ Completed |
| message-generator | Генератор тестовых сообщений (1–10000, 5 типов процессов) | ✅ Completed |
| hybrid-strategy | Парсер `hybrid`: flat columns + JSON blobs | ✅ Completed |
| eav-strategy | Парсер `eav`: events + variables (полный flatten) | ✅ Completed |
| combined-strategy | Парсер `combined`: Tier 1/2/3, warm-лимит 10..1010 | ✅ Completed |
| compcom-strategy | Парсер `compcom`: compact combined без cold blob | ✅ Completed |
| default-strategy | Парсер `default`: все поля как колонки (`variables.<path>`) | ✅ Completed |
| druid-client | HTTP-клиент: ingestion (`index_parallel`), SQL, TLS, basic auth, retry | ✅ Completed |
| queries-all | SQL-запросы по всем стратегиям + manifest (`scripts/query-manifest.json`) | ✅ Completed |
| query-suite | Команда `query-suite`: прогон SQL с median/p95 и отчётом | ✅ Completed |
| tests | Unit-тесты парсеров, metastore, Druid-клиента; integration (Testcontainers) | ✅ Completed |
| linux-host-bundle | Gradle-задача `linuxHostBundle`, `gradlew-linux-host`, DEPLOYMENT.md | ✅ Completed |
| run-all-pipeline | `scripts/run-all-strategies.sh`, nohup/systemd units, defaults из `config.yaml` | ✅ Completed |
| schema-metastore | Опциональный PostgreSQL metastore схем сообщений (feature flag) | ✅ Completed |
| metrics-logging | Структурированные `TEMP_PERF` / `ANALYSIS` логи, docs по метрикам | ✅ Completed |
| default-warm-design | Дизайн `default_warm` + индексация массивов (object support) | 📋 Planned |
| default-warm-impl | Реализация стратегии `default_warm` (2 datasource) | 📋 Planned |

---

## Архитектура проекта

```
work_parser/
├── build.gradle.kts                 # Gradle, зависимости, custom tasks
├── settings.gradle.kts
├── gradle.properties
├── config.yaml                        # Druid, runAllArgs, schemaMetastore
├── .env.example
├── src/
│   ├── main/
│   │   ├── kotlin/ru/sber/parser/
│   │   │   ├── Application.kt               # CLI: generate, parse, query, query-suite
│   │   │   ├── config/
│   │   │   │   ├── AppConfig.kt             # ENV > YAML > defaults (Hoplite/Jackson)
│   │   │   │   └── FieldClassification.kt   # Tier 1/2/3 field config
│   │   │   ├── model/
│   │   │   │   ├── BpmMessage.kt
│   │   │   │   ├── NodeInstance.kt
│   │   │   │   └── druid/                   # HybridRecord, EavRecord, CombinedRecord, DefaultRecord
│   │   │   ├── parser/
│   │   │   │   ├── MessageParser.kt
│   │   │   │   ├── VariableFlattener.kt
│   │   │   │   └── strategy/
│   │   │   │       ├── ParseStrategy.kt
│   │   │   │       ├── HybridStrategy.kt
│   │   │   │       ├── EavStrategy.kt
│   │   │   │       ├── CombinedStrategy.kt
│   │   │   │       ├── CompcomStrategy.kt
│   │   │   │       └── DefaultStrategy.kt
│   │   │   ├── druid/
│   │   │   │   ├── DruidClient.kt
│   │   │   │   ├── DruidDataSources.kt      # Scope-нутые имена datasource
│   │   │   │   ├── IngestionSpec.kt
│   │   │   │   └── SchemaGenerator.kt
│   │   │   ├── metastore/                   # Опциональный PostgreSQL schema registry
│   │   │   │   ├── SchemaExtractor.kt
│   │   │   │   ├── SchemaHasher.kt
│   │   │   │   ├── SchemaRegistryService.kt
│   │   │   │   └── PostgresSchemaMetastoreRepository.kt
│   │   │   └── generator/
│   │   │       ├── MessageGenerator.kt
│   │   │       └── GeneratorRunner.kt
│   │   └── resources/
│   │       ├── logback.xml
│   │       └── sql/metastore_schema.sql
│   └── test/kotlin/...                        # 13 test-классов, 118 @Test
├── query/
│   ├── hybrid/       # 61 SQL
│   ├── eav/          # 69 SQL
│   ├── combined/     # 73 SQL
│   ├── compcom/      # 66 SQL
│   └── default/      # 13 SQL
├── scripts/
│   ├── run-all-strategies.sh
│   ├── work-parser-run-all.service      # systemd units
│   ├── work-parser-run-all@.service
│   ├── clean-druid-remote.sh
│   ├── clean-run-data.sh
│   ├── create-druid-truststore.sh
│   ├── generate_queries.py
│   └── query-manifest.json
├── distribution/
│   ├── DEPLOYMENT.md
│   └── cert/
├── docs/                                      # Метрики, Gradle tasks, дизайн-доки
├── messages/                                  # Сгенерированные тестовые сообщения
├── query-results/                             # Результаты query-suite
├── samples/                                   # Исходные образцы JSON
├── strategies.md                              # Описание стратегий (актуальная реализация)
└── README.md
```

---

## Реализованные компоненты

### 1. Gradle и зависимости

`build.gradle.kts` — Java 17, Kotlin 1.9.22, fat JAR:

| Категория | Библиотеки |
|-----------|------------|
| JSON/YAML | Jackson (core, kotlin, jsr310, yaml) |
| HTTP | Ktor Client (CIO, auth, logging, jackson) |
| Async | kotlinx-coroutines |
| Config | Hoplite (yaml) + ENV override в `AppConfig` |
| Logging | SLF4J + Logback |
| Metastore | PostgreSQL JDBC, HikariCP |
| Testing | JUnit 5, MockK, Ktor Mock, Testcontainers (PostgreSQL) |

Custom Gradle-задачи: `linuxHostBundle`, `verifyLinuxHostBundleScriptModes`, `generateQueries`, `generateCompcomQueries`, `verifyQueryManifest`.

### 2. Domain-модели

`BpmMessage` — полная модель экземпляра процесса:

- Идентификаторы: `id`, `processId`, `rootInstanceId`, `parentInstanceId`, `businessKey`
- Метаданные: `processName`, `state`, `startDate`, `endDate`, `moduleId`, `version`
- BAM/инфра: `bamProjectId`, `extIds`, `engineVersion`, `enginePodName`, `retryCount`, `ownerRole`, `idempotencyKey`, `operation`, `contextSize`
- Данные: `nodeInstances` (список `NodeInstance`), `variables` (динамический `Map`)

### 3. Генератор тестовых сообщений

`MessageGenerator.generateAll(outputDir, count)` — count от 1 до 10000 (CLI default: 500).

Типы процессов (на основе `samples/`):

| processName | Основа |
|-------------|--------|
| uvskRemainderReturnCR-Service | message2.json |
| uvskStupidsEarlyRehrenment_sub | message1.json |
| MassTransferProcessUnif | message3.json |
| uvskFraudFin_front | query2 сценарии |
| tappeal_p2p_receiver | query3 сценарии |

Вариативность: state (0–4), nodeType, временные диапазоны, epkId/caseId/fio, epkData/staticData/tracingHeaders/answerGFL.

### 4. Стратегии парсинга

Подробное описание: `strategies.md`. Datasource-имена scope-нуты по стратегии (`DruidDataSources.kt`).

| Стратегия | Datasource(s) | Записей/сообщение | Суть |
|-----------|---------------|-------------------|------|
| `hybrid` | `hybrid_process_hybrid` | 1 | Hot columns + structured + JSON blobs |
| `eav` | `eav_process_events`, `eav_process_variables` | 1 + N | Полный flatten переменных |
| `combined` | `combined_process_main`, `combined_process_variables_indexed` | 1 + N(warm) | Tier hot/warm/cold + warm-лимит |
| `compcom` | `compcom_process_main_compact`, `compcom_process_variables_indexed` | 1 + N(warm) | Combined без `var_blob_json` |
| `default` | `default_process_default` | 1 | Все поля как колонки (`variables.<path>`) |

**Warm-лимит** (`PARSER_WARM_VARIABLES_LIMIT` / `config.yaml`): для `combined`/`compcom` — `<10` отключает, `10..1010` применяется, `>1010` обрезается до 1010.

**Ingestion:** `index_parallel`, inline NDJSON, батчи по `batchSize` и `maxInlineBytes`, retry при ошибках Overlord.

### 5. Druid-клиент

`DruidClient`:

- SQL-запросы через Broker (`query`, `tryQuery` с метриками)
- Batch ingestion через Overlord (`ingest`)
- Failover по спискам URL (`brokerUrls`, `coordinatorUrls`, `overlordUrls`, `routerUrls`)
- TLS truststore, basic auth, `insecureSkipTlsVerify`
- Верификация datasource после ingest

### 6. Schema Metastore (опционально)

При `schemaMetastore.enabled=true` в `config.yaml`:

1. После парсинга JSON, до трансформации — регистрация схемы в PostgreSQL
2. `SchemaExtractor` → canonical JSON schema → SHA-256 fingerprint
3. Таблицы `message_schema`, `message_schema_binding` (`src/main/resources/sql/metastore_schema.sql`)
4. При `enabled=false` (default) — pipeline без изменений, PostgreSQL не инициализируется

### 7. CLI-команды

```
generate [output-dir] [count]     # default: messages, 500
parse <strategy> [input-dir]      # hybrid|eav|combined|compcom|default
parse <strategy> --ingest         # + загрузка в Druid
query <query-file.sql>
query-suite <strategy>            # все SQL из query/<strategy>/, median/p95
help
```

Флаги `query-suite`: `--repeat N`, `--out <path>`, `--segment-sizes`.

### 8. SQL-запросы

| Стратегия | Файлов | Генерация |
|-----------|--------|-----------|
| hybrid | 61 | `scripts/generate_queries.py` + manifest |
| eav | 69 | manifest |
| combined | 73 | manifest |
| compcom | 66 | manifest (из combined rules) |
| default | 13 | ручные / частично manifest |
| **Итого** | **282** | `./gradlew verifyQueryManifest` |

Категории: SELECT, filtering, aggregations, time-based, JSON access, JOINs, complex, performance.

### 9. Пакетный запуск (Linux host)

`scripts/run-all-strategies.sh`:

1. Очистка `logs/`, `query-results/`, `messages/`
2. Очистка Druid datasource (`clean-druid-remote.sh`)
3. `generate messages <N>`
4. `parse <strategy> messages --ingest` для: `combined`, `compcom`, `eav`, `hybrid`, `default`
5. `query-suite <strategy>` → `query-results/<strategy>.txt`

Параметры: `-m` (count), `-w` (warm variants), `--skip-generate`. Defaults из `config.yaml` (`runAllArgs`) или `RUN_ALL_ARGS`.

Systemd: `scripts/work-parser-run-all.service`, `work-parser-run-all@.service`.

Distribution: `./gradlew linuxHostBundle` → ZIP с JAR, scripts, query, config, markdown.

### 10. Тесты

13 test-классов, 118 `@Test`:

- Парсеры: `HybridStrategyTest`, `EavStrategyTest`, `CombinedStrategyTest`, `VariableFlattenerTest`, `MessageParserTest`
- Config: `FieldClassificationTest`
- Generator: `MessageGeneratorTest`
- Druid: `DruidClientTest`, `DruidIntegrationTest`
- Metastore: `SchemaExtractorTest`, `SchemaRegistryServiceTest`, `PostgresSchemaMetastoreRepositoryTest` (Testcontainers)

---

## Конфигурация

Приоритет: **ENV → config.yaml → defaults**.

Основные ENV:

| Переменная | Назначение |
|------------|------------|
| `DRUID_BROKER_URL` / `DRUID_BROKER_URLS` | SQL-запросы |
| `DRUID_COORDINATOR_URL` / `DRUID_COORDINATOR_URLS` | Управление сегментами |
| `DRUID_OVERLORD_URL` / `DRUID_OVERLORD_URLS` | Ingestion tasks |
| `DRUID_ROUTER_URL` / `DRUID_ROUTER_URLS` | Router |
| `DRUID_USERNAME`, `DRUID_PASSWORD` | Basic auth |
| `DRUID_TRUST_STORE_PATH`, `DRUID_TRUST_STORE_PASSWORD` | TLS |
| `DRUID_BATCH_SIZE`, `DRUID_MAX_INLINE_BYTES` | Ingestion batching |
| `PARSER_WARM_VARIABLES_LIMIT` | Warm-лимит для combined/compcom |
| `PARSER_CONFIG_PATH` | Путь к config.yaml |
| `SCHEMA_METASTORE_ENABLED` | Включение PostgreSQL metastore |

Шаблон: `.env.example`.

---

## Ключевые технические решения

1. **Strategy Pattern** — единый `ParseStrategy`, `transformBatch()` для multi-datasource стратегий
2. **Scope-нутые datasource** — `hybrid_process_*`, `eav_process_*` и т.д., данные стратегий не смешиваются
3. **Конфигурируемая классификация** — `FieldClassification` (Tier 1 hot, Tier 2 warm categories, Tier 3 cold blobs) в YAML
4. **Безопасный inline ingestion** — лимит `maxInlineBytes` (256KB default) ниже ZooKeeper znode limit
5. **Двухрежимный pipeline** — baseline (без metastore) и расширенный (с регистрацией схем)
6. **Структурированные метрики** — `TEMP_PERF` (latency, throughput) и `ANALYSIS` (schema, datasource) в логах
7. **Query manifest** — Python-генератор SQL с `--check` для CI-верификации

---

## Планируемая работа

### `default_warm` (дизайн готов, реализация — нет)

Документ: `docs/default_warm_arrays_design.md`.

Цель: заменить полный flatten `default` на компактную модель:

- `default_warm_main` — фиксированные process + warm-колонки, без JSON-массивов целиком
- `default_warm_arrays_indexed` — индексация массивов по whitelist-путям (логика `compcom`), включая object-поля

Режимы обработки массивов: `leaf_scalar`, `leaf_object_flatten`, `raw_object_json`.

---

## Критические поля (Tier 1)

На основе анализа `samples/` и SQL-запросов:

- Process: `id`, `processName`, `state`, `startDate`, `endDate`, `rootInstanceId`, `processId`, `moduleId`, `businessKey`
- Variables top-level: `caseId`, `epkId`, `fio`, `status`, `ucpId`, `globalInstanceId`
- staticData: `clientEpkId`, `casePublicId`, `registrationTime`, `statusCode`, `closedTime`
- epkData: `epkEntity.ucpId`, `epkEntity.names[*]`, `epkEntity.phoneNumbers[*]`
- tracingHeaders: `x-request-id`, `x-b3-traceid`
- Cold (combined only): `answerGFL`, `opHistory`, `gflData`
- Nodes: `nodeName`, `nodeType`, `state`, `triggerTime`, `leaveTime`, `error`

---

## Ссылки

| Документ | Содержание |
|----------|------------|
| `README.md` | Быстрый старт, CLI, runbook |
| `strategies.md` | Детали всех 5 стратегий |
| `distribution/DEPLOYMENT.md` | Развёртывание linux-host bundle |
| `docs/GRADLE_TASKS.md` | Gradle-задачи |
| `docs/default_warm_arrays_design.md` | Дизайн будущей стратегии |
| `docs/message_schema_metastore_plan.md` | План metastore (реализован) |
| `docs/PARSING_METRICS_2LOGS_ALL_STRATEGIES.md` | Метрики парсинга |
| `docs/QUERY_RESULTS_METRICS_ALL_STRATEGIES.md` | Метрики SQL-прогонов |
