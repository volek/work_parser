# Стратегия хранения и выборок в Druid: `default`

Документ описывает фактическую реализованную логику в коде проекта после унификации стратегий.

## Общий пайплайн (для всех стратегий)

1. Команда `parse <strategy> [input-dir]` читает все JSON-файлы из директории (`Application.kt`).
2. `MessageParser` десериализует каждый JSON в `BpmMessage`.
3. Выбранная стратегия (`DefaultStrategy`) делает `transform(message)`.
4. На выходе стратегия возвращает `List<Map<String, Any?>>` в формате, пригодном для Druid ingestion.
5. При `--ingest` `DruidClient.ingest(...)` отправляет inline `index_parallel` task на Overlord:
  - `__time` используется как timestamp-колонка (формат `millis`)
  - остальные поля становятся dimensions
  - данные передаются как NDJSON;
  - перед отправкой записи режутся на безопасные батчи с учетом:
    - `druid.batchSize` (максимум записей в батче),
    - `druid.maxInlineBytes` (максимальный размер inline NDJSON payload).

## `default` стратегия

## Идея модели

`Default` = основная таблица с обязательными верхнеуровневыми полями + отдельная таблица для всех массивов из `variables`.

- Основной datasource: `default_process_default`.
- Дополнительный datasource массивов: `default_process_variables_array_indexed`.
- В основной записи сохраняются только обязательные top-level поля и warm leaf-поля не-массивной природы.
- Для warm-набора используется конфигурация `tier2WarmCategories` c wildcard `[*]`, как в compcom-подходе.
- Все array-path значения пишутся в отдельный datasource с полями `var_category`, `var_path`, `var_value`, `var_type`, `value_json`.
- Поддерживаются:
  - конфиг глубины парсинга массивов (`parser.arrayMaxDepth`);
  - JSON blob для вложенных объектов в массивах (`parser.arrayObjectJsonBlobEnabled`).

Плюс: управляемая схема, массивы вынесены в отдельный datasource, warm-пути конфигурируемы.  
Минус: аналитика по массивам требует отдельного источника.

## Как работает парсинг

`DefaultStrategy.transformBatch(messages)`:
- формирует main record с обязательными top-level полями;
- извлекает warm-поля по `tier2WarmCategories`;
- не-массивные warm leaf значения пишет в `variables.<path>` основной записи;
- все массивные значения и JSON blobs массивных объектов пишет в `default_process_variables_array_indexed`;
- применяет лимит глубины массивов и лимит числа warm-переменных (если задан).

## Как выбирать данные из Druid (default)

Типовые паттерны:
- по main: `WHERE process_name = '...' AND state = 1`
- по warm не-массивам: `WHERE variables.staticData.caseId = '...'`
- по массивам: выборка из `default_process_variables_array_indexed` по `var_category` + `var_path`
- по blob-объектам массива: фильтрация по `value_json` через `JSON_VALUE`.


