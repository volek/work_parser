# Стратегии хранения и выборок в Druid: `combined`, `eav`, `hybrid`

Документ описывает **фактическую реализованную логику** в коде проекта: как сообщения парсятся, как сериализуются в записи для Apache Druid, и как по ним выполняются выборки.

## Общий пайплайн (для всех стратегий)

1. Команда `parse <strategy> [input-dir]` читает все JSON-файлы из директории (`Application.kt`).
2. `MessageParser` десериализует каждый JSON в `BpmMessage`.
3. Выбранная стратегия (`HybridStrategy` / `EavStrategy` / `CombinedStrategy`) делает `transform(message)`.
4. На выходе стратегия возвращает `List<Map<String, Any?>>` в формате, пригодном для Druid ingestion.
5. При `--ingest` `DruidClient.ingest(...)` отправляет inline `index_parallel` task на Overlord:
  - `__time` используется как timestamp-колонка (формат `millis`)
  - остальные поля становятся dimensions
  - данные передаются как NDJSON.

---

## 1) `hybrid` стратегия

## Идея модели

`Hybrid` = **один datasource** с комбинацией:

- hot-поля как отдельные колонки (быстрые фильтры),
- structured-поля из ключевых вложенных объектов,
- cold JSON-блобы для полноты.

Datasource: `process_hybrid`.

## Как работает парсинг

`HybridStrategy.transform(message)` всегда возвращает **одну запись** (`HybridRecord`) на процесс.

Что извлекается:

- Базовые метаданные процесса: `process_id`, `process_name`, `state`, `module_id`, `business_key`, `version`, `end_date`, `error`.
- Hot top-level поля (из `tier1HotColumns`, только верхний уровень): `caseId`, `epkId`, `fio`, `ucpId`, `status`, `globalInstanceId`, `INTERACTION_ID`, `INTERACTION_DATE`, `theme`, `result`.
- Structured поля:
  - из `staticData`: `caseId`, `clientEpkId`, `casePublicId`, `statusCode`, `registrationTime`, `closedTime`, `classifierVersion`;
  - из `epkData.epkEntity`: `ucpId`, `clientStatus`, `gender`;
  - из `tracingHeaders`: `x-request-id`, `x-b3-traceid`.
- JSON-блобы:
  - `node_instances_json` (всегда),
  - `var_epkData_json`,
  - `var_staticData_json`,
  - `var_answerGFL_json` (если `answerGFL` есть в cold tier),
  - `var_other_json` (все переменные вне hot/cold/structured).

## Формат хранения в Druid

Одна строка в `process_hybrid` на одно BPM-сообщение.

Ключевые колонки:

- Временная: `__time` (epoch millis из `startDate`).
- Идентификатор: `process_id`.
- Hot: `var_caseId`, `var_epkId`, `var_fio`, `var_ucpId`, ...
- Structured: `var_staticData_`*, `var_epkData_*`, `var_tracingHeaders_*`.
- JSON: `node_instances_json`, `var_epkData_json`, `var_staticData_json`, `var_answerGFL_json`, `var_other_json`.

Плюс: без JOIN, быстрые фильтры по частым полям.  
Минус: wide-таблица, часть колонок часто `NULL`.

## Как выбирать данные из Druid (hybrid)

Типовые паттерны:

- Фильтрация по hot-полям:
  - `WHERE var_epkId = '...'`
  - `WHERE process_name = '...' AND state = 1`
- JSON-извлечение:
  - `JSON_VALUE(var_epkData_json, '$.epkEntity.names[0].surname')`
  - `JSON_VALUE(var_answerGFL_json, '$.Status.StatusCode')`
- Аналитика по времени:
  - `WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR`
  - группировки по `process_name`, `state`, `module_id`.

Примеры в проекте: `query/hybrid/q01_select_all.sql`, `query/hybrid/q04_filter_by_epkId.sql`, `query/hybrid/q41_json_answerGFL_check.sql`, `query/hybrid/q51_json_surname_extract.sql`.

---

## 2) `eav` стратегия

## Идея модели

`EAV` (Entity-Attribute-Value) = **два datasource**:

- `process_events` — 1 запись на процесс (метаданные),
- `process_variables` — N записей на процесс (переменные как пары path/value/type).

## Как работает парсинг

`EavStrategy.transform(message)` делает:

1. `createEventRecord(...)` -> 1 `ProcessEventRecord`.
2. `createVariableRecords(...)`:
  - `VariableFlattener.flatten(message.variables)` рекурсивно сплющивает JSON:
    - вложенность: `a.b.c`
    - массивы: `items[0].name`
  - для каждого leaf-значения создается `ProcessVariableRecord(process_id, __time, var_path, var_value, var_type)`.

Поддерживаемые `var_type`:

- `string`, `number`, `boolean`, `date`, `json`, `null`.

## Формат хранения в Druid

### `process_events`

- `process_id`, `__time`, `process_name`, `state`, `module_id`, `business_key`, `root_instance_id`, `parent_instance_id`, `version`, `end_date`, `error`, `node_instances_json`.

### `process_variables`

- `process_id`, `__time`, `var_path`, `var_value`, `var_type`.

Кардинальность:

- 1 строка в `process_events` + много строк в `process_variables` на каждое сообщение.

Плюс: максимальная гибкость без миграций схемы при новых переменных.  
Минус: тяжелее аналитика «по процессу целиком», часто нужен JOIN/self-JOIN.

## Как выбирать данные из Druid (eav)

Типовые паттерны:

- Получить все значения конкретного атрибута:
  - `WHERE var_path = 'epkId'`
- Найти вложенные атрибуты:
  - `WHERE var_path LIKE 'epkData.epkEntity.%'`
- Собрать несколько атрибутов в одну строку:
  - self-JOIN `process_variables` по `process_id` для `epkId`, `caseId`, `fio`, и т.д.
- Связать метаданные процесса и переменные:
  - JOIN `process_events` + `process_variables` по `process_id` (и при необходимости по `__time`).

Примеры в проекте: `query/eav/q01_select_events.sql`, `query/eav/q02_select_variables.sql`, `query/eav/q03_join_epkId.sql`, `query/eav/q04_join_caseId.sql`, `query/eav/q07_nested_epkData.sql`.

---

## 3) `combined` стратегия

## Идея модели

`Combined` = tier-подход с **двумя datasource**:

- `process_main` — основная wide-строка на процесс (hot + structured + cold blob),
- `process_variables_indexed` — индексированные warm-переменные (`var_category`, `var_path`, `var_value`, `var_type`).

Это компромисс между `hybrid` (быстро и просто) и `eav` (гибко).

## Как работает парсинг

`CombinedStrategy.transform(message)` делает:

1. `createMainRecord(...)`:
  - пишет базовые поля процесса;
  - вынимает hot и structured поля (аналогично hybrid);
  - сериализует `node_instances_json`;
  - собирает cold-переменные из `tier3ColdBlobs` в `var_blob_json`.
2. `createWarmVariableRecords(...)`:
  - для каждой категории из `tier2WarmCategories` (`epkData`, `staticData`, `tracingHeaders`, `startAttributes`, `inputCC`, `inputDC`) извлекает объект;
  - сплющивает через `VariableFlattener`;
  - фильтрует пути по конфигурации категории, включая wildcard `[*]`;
  - создает запись в `process_variables_indexed`.

## Формат хранения в Druid

### `process_main`

- Метаданные процесса + hot/structured колонки.
- JSON: `node_instances_json`, `var_blob_json`.
- 1 строка на сообщение.

### `process_variables_indexed`

- `process_id`, `__time`, `var_category`, `var_path`, `var_value`, `var_type`.
- N строк на сообщение только по warm-категориям/разрешенным путям.

Плюс: частые фильтры быстрые, редкие атрибуты доступны через индексный слой.  
Минус: два datasource, усложнение запросов/загрузки.

## Как выбирать данные из Druid (combined)

Типовые паттерны:

- Hot-поиск в `process_main`:
  - `WHERE var_epkId = '...'`
  - `WHERE var_caseId = '...'`
- Warm-поиск в `process_variables_indexed`:
  - `WHERE var_category = 'epkData' AND var_path = 'epkEntity.ucpId'`
- Объединение main + indexed:
  - JOIN по `process_id` (и при необходимости по времени).
- Доступ к cold-части:
  - `JSON_VALUE(var_blob_json, '$.answerGFL...')` и подобные JSON-path выборки.

Примеры в проекте: `query/combined/q01_select_main.sql`, `query/combined/q02_select_indexed_vars.sql`, `query/combined/q03_filter_epkId.sql`, `query/combined/q06_join_with_indexed.sql`, `query/combined/q09_gfl_category.sql`.

---

## 4) `default` стратегия

## Идея модели

`Default` = «плоская» wide‑таблица: **один datasource, все поля сообщения как отдельные колонки**.

- Datasource: `process_default`.
- Поля верхнего уровня (`id`, `process_id`, `process_name`, `state`, `start_date` и т.п.) хранятся в `snake_case`.
- Все переменные процесса пишутся как колонки c префиксом `variables.` и путём через точку:
  - `variables.caseId`
  - `variables.staticData.clientEpkId`
  - `variables.epkData.epkEntity.ucpId`
- `node_instances` хранится в одной колонке как JSON‑строка.

Плюс: самая простая модель, **нет JOIN и нет EAV‑слоя**, любые переменные сразу доступны как колонки.  
Минус: очень wide‑таблица, число колонок растёт с разнообразием переменных; схема менее контролируема по сравнению с `hybrid`/`combined`.

## Как работает парсинг

`DefaultStrategy.transform(message)`:

- ставит `__time` = `startDate` в millis;
- заполняет все поля верхнего уровня в `snake_case`;
- сериализует `node_instances` в JSON‑строку колонку `node_instances`;
- сплющивает `variables` через `VariableFlattener` и для каждого leaf‑значения создаёт колонку `variables.<path>`.

На выходе всегда **одна запись на сообщение**.

## Как выбирать данные из Druid (default)

Типовые паттерны:

- Фильтрация по метаданным процесса:
  - `WHERE process_name = '...' AND state = 1`
- Поиск по переменным:
  - `WHERE variables.caseId = '...'`
  - `WHERE variables.epkData.epkEntity.ucpId = '...'`
- Вытаскивание вложенных структур из `node_instances`:
  - `JSON_VALUE(node_instances, '$[0].nodeId')` (если нужна работа с JSON‑массивом нод).

---

## Важный нюанс текущей реализации CLI ingestion

В `Application.kt` при `parse <strategy> --ingest` выполняется:

- `messages.flatMap { strategy.transform(it) }`,
- затем **один вызов** `client.ingest(strategy.dataSourceName, records)`.

Для `hybrid` это корректно (один datasource).  
Для `eav` и `combined` это означает, что записи обоих типов формируются, но отправляются одним потоком в `dataSourceName` стратегии, если не использовать отдельную группировку и отдельные ingestion-вызовы по каждому datasource.

Для корректной двухтабличной загрузки нужно использовать логику `transformBatch(...)` + раздельный `ingest` для каждого ключа `Map<dataSource, records>`.

---

## Быстрое сравнение


| Стратегия  | Datasource                                  | Записей на 1 процесс | Сильная сторона                    | Ограничение                      |
| ---------- | ------------------------------------------- | -------------------- | ---------------------------------- | -------------------------------- |
| `hybrid`   | `process_hybrid`                            | 1                    | Простые и быстрые запросы без JOIN | Частично wide/NULL-heavy         |
| `eav`      | `process_events`, `process_variables`       | 1 + N                | Максимальная гибкость схемы        | Сложные JOIN/self-JOIN           |
| `combined` | `process_main`, `process_variables_indexed` | 1 + N(warm)          | Баланс скорости и гибкости         | 2 datasource и сложнее ingestion |
| `default`  | `process_default`                           | 1                    | Максимально простой мэппинг полей  | Очень wide‑таблица, динамическая |


