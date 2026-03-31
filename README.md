# BPM Message Parser для Apache Druid

Kotlin CLI-приложение для парсинга BPM-сообщений и загрузки в Apache Druid.

## Статус поддержки

Поддерживается только запуск на Linux host (bash + Java 17 + `./gradlew`).

- Docker/Compose сценарии удалены.
- Windows PowerShell сценарии удалены.
- Основной runbook: `README.md` и `distribution/DEPLOYMENT.md`.

## Содержание

- [Требования](#требования)
- [Быстрый старт Linux host](#быстрый-старт-linux-host)
- [Конфигурация](#конфигурация)
- [Команды CLI](#команды-cli)
- [Пакетный запуск всех стратегий](#пакетный-запуск-всех-стратегий)
- [Стратегии парсинга](#стратегии-парсинга)
- [Gradle задачи](#gradle-задачи)
- [Очистка данных](#очистка-данных)
- [Устранение неполадок](#устранение-неполадок)

## Требования

- Java 17 (JDK для сборки, JRE достаточно для запуска JAR)
- Linux host с bash
- Python 3 для задач генерации/проверки SQL manifest
- Доступ к Apache Druid для `--ingest` и `query`

## Быстрый старт Linux host

```bash
chmod +x gradlew scripts/*.sh
./gradlew jar
java -jar build/libs/bpm-druid-parser-1.0.0.jar help
```

Для Linux-host можно использовать обертку `./gradlew-linux-host` (поведение как у `gradlew`, с fallback на системный `gradle`, если отсутствует `gradle-wrapper.jar`):

```bash
chmod +x gradlew-linux-host
./gradlew-linux-host clean test jar
```

Примеры:

```bash
java -jar build/libs/bpm-druid-parser-1.0.0.jar generate messages 100
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse hybrid messages --ingest
java -jar build/libs/bpm-druid-parser-1.0.0.jar query query/hybrid/q01_select_all.sql
```

Для поставки на отдельный Linux host:

```bash
./gradlew linuxHostBundle verifyLinuxHostBundleScriptModes
```

Инструкция по архиву: `distribution/DEPLOYMENT.md`.

## Конфигурация

Приоритет источников:

1. ENV переменные
2. `config.yaml`
3. значения по умолчанию

Основные ENV:

- `DRUID_BROKER_URL`
- `DRUID_BROKER_URLS` (список через запятую)
- `DRUID_COORDINATOR_URL`
- `DRUID_COORDINATOR_URLS` (список через запятую)
- `DRUID_OVERLORD_URL`
- `DRUID_OVERLORD_URLS` (список через запятую)
- `DRUID_ROUTER_URL`
- `DRUID_ROUTER_URLS` (список через запятую)
- `DRUID_CONNECT_TIMEOUT`
- `DRUID_READ_TIMEOUT`
- `DRUID_BATCH_SIZE`
- `PARSER_WARM_VARIABLES_LIMIT` (для `combined`/`compcom`)

Используйте шаблон: `.env.example`.

## Команды CLI

```bash
# Справка
java -jar build/libs/bpm-druid-parser-1.0.0.jar help

# Генерация test data
java -jar build/libs/bpm-druid-parser-1.0.0.jar generate [output-dir] [count]

# Парсинг
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse <strategy> [input-dir]

# Парсинг + ingestion
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse <strategy> [input-dir] --ingest

# Один SQL файл
java -jar build/libs/bpm-druid-parser-1.0.0.jar query <query-file.sql>

# Набор SQL по стратегии
java -jar build/libs/bpm-druid-parser-1.0.0.jar query-suite <strategy>
```

Доступные стратегии:

- `hybrid`
- `eav`
- `combined`
- `compcom`
- `default`

## Пакетный запуск всех стратегий

Скрипт `scripts/run-all-strategies.sh` выполняет:

1. Очистку `logs/`, `query-results/`, `messages/`
2. Очистку parser datasource через `scripts/clean-druid-remote.sh`
3. `generate messages <N>`
4. `parse <strategy> messages --ingest` для всех стратегий
5. Прогон всех SQL из `query/<strategy>/` в `query-results/<strategy>.txt`

Запуск:

```bash
./gradlew jar
./scripts/run-all-strategies.sh
./scripts/run-all-strategies.sh -m 100
./scripts/run-all-strategies.sh -w 10,110,210
```

## Стратегии парсинга

| Стратегия | Описание | Таблицы |
|-----------|----------|---------|
| `hybrid` | Flat columns + JSON blobs | 1 |
| `eav` | Entity-Attribute-Value | 2 |
| `combined` | Tiered Hot/Warm/Cold с cold blob | 2 |
| `compcom` | Compact combined без cold blob | 2 |
| `default` | Все поля как отдельные колонки | 1 |

Подробнее: `strategies.md`.

## Gradle задачи

Часто используемые:

```bash
./gradlew clean build
./gradlew test
./gradlew generateQueries
./gradlew verifyQueryManifest
./gradlew linuxHostBundle verifyLinuxHostBundleScriptModes
```

Полный список: `docs/GRADLE_TASKS.md`.

## Очистка данных

Локальная очистка:

```bash
./scripts/clean-run-data.sh
./scripts/clean-run-data.sh --full
```

Очистка parser datasource в удаленном Druid:

```bash
COORDINATOR_URL="http://192.168.1.27:8081" ./scripts/clean-druid-remote.sh
```

## Устранение неполадок

- `Unsupported class file major version` -> запущена Java ниже 17.
- ingestion/query ошибки соединения -> проверьте `DRUID_*_URL`.
- `JAR not found` -> выполните `./gradlew jar` и проверьте `build/libs/`.
- ошибки SQL manifest -> `./gradlew verifyQueryManifest`.
