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
- [Фоновый запуск run-all (nohup)](#фоновый-запуск-run-all-strategiessh-nohup)
- [Конфигурация](#конфигурация)
- [Команды CLI](#команды-cli)
- [Пакетный запуск (default)](#пакетный-запуск-default)
- [Стратегия парсинга](#стратегия-парсинга)
- [Gradle задачи](#gradle-задачи)
- [Очистка данных](#очистка-данных)
- [Linux host runbook (новая стратегия)](#linux-host-runbook-новая-стратегия)
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
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse default messages --ingest
java -jar build/libs/bpm-druid-parser-1.0.0.jar query query/default/q01_select_all.sql
```

Для поставки на отдельный Linux host:

```bash
./gradlew linuxHostBundle verifyLinuxHostBundleScriptModes
```

Инструкция по архиву: `distribution/DEPLOYMENT.md`.

## Фоновый запуск `run-all-strategies.sh` (nohup)

Запуск можно отвязать от текущей консоли через `nohup`:

```bash
mkdir -p logs
nohup ./scripts/run-all-strategies.sh -m 100 > logs/run-all.nohup.log 2>&1 < /dev/null &
echo $! > logs/run-all.pid
```

Или через helper-скрипт:

```bash
chmod +x scripts/run-all-nohup.sh
./scripts/run-all-nohup.sh start -m 100
```

Команды `scripts/run-all-nohup.sh`:

- `start [args...]` — запускает `run-all-strategies.sh` в фоне через `nohup`, пишет PID в `logs/run-all.pid`.
- `status` — показывает, запущен ли процесс, и печатает `ps` по сохраненному PID.
- `logs` — открывает `tail -f logs/run-all.nohup.log`.
- `stop` — останавливает процесс по PID и очищает `logs/run-all.pid`.

Важно: параметры запуска (`-m`, `-w`, `-a`, `--skip-generate`) передаются только через CLI, например `./scripts/run-all-nohup.sh start -m 200 --skip-generate`.

Быстрое использование `scripts/run-all-nohup.sh`:

```bash
# запуск в фоне
./scripts/run-all-nohup.sh start -m 100 -w 10,110,210

# проверка статуса
./scripts/run-all-nohup.sh status

# просмотр live-лога
./scripts/run-all-nohup.sh logs

# остановка
./scripts/run-all-nohup.sh stop
```

Файлы, которые использует скрипт:

- PID: `logs/run-all.pid`
- nohup-лог: `logs/run-all.nohup.log`

Проверка статуса и логов:

```bash
ps -fp "$(cat logs/run-all.pid)"
tail -f logs/run-all.nohup.log
```

Через helper-скрипт:

```bash
./scripts/run-all-nohup.sh status
./scripts/run-all-nohup.sh logs
```

Остановка фонового запуска:

```bash
kill "$(cat logs/run-all.pid)"
```

Или:

```bash
./scripts/run-all-nohup.sh stop
```

Передача параметров при `nohup` — как в обычном запуске скрипта:

```bash
nohup ./scripts/run-all-strategies.sh -m 200 -w 10,110,210 --skip-generate > logs/run-all.nohup.log 2>&1 < /dev/null &
echo $! > logs/run-all.pid
```

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
- `PARSER_WARM_VARIABLES_LIMIT` (лимит warm-полей в `default`)
- `PARSER_ARRAY_MAX_DEPTH` (глубина разбора массивов в `default`)

Используйте шаблон: `.env.example`.

## Команды CLI

```bash
# Справка
java -jar build/libs/bpm-druid-parser-1.0.0.jar help

# Генерация test data
java -jar build/libs/bpm-druid-parser-1.0.0.jar generate [output-dir] [count]

# Парсинг
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse default [input-dir]

# Парсинг + ingestion
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse default [input-dir] --ingest

# Один SQL файл
java -jar build/libs/bpm-druid-parser-1.0.0.jar query <query-file.sql>

# Набор SQL по стратегии
java -jar build/libs/bpm-druid-parser-1.0.0.jar query-suite default
```

Поддерживаемая стратегия:

- `default`

## Пакетный запуск (default)

Скрипт `scripts/run-all-strategies.sh` выполняет:

1. Очистку `logs/`, `query-results/`, `messages/`
2. Очистку parser datasource через `scripts/clean-druid-remote.sh --target default`
3. `generate messages <N>`
4. `parse default messages --ingest`
5. Прогон SQL из `query/default/` в `query-results/default.txt`

Запуск:

```bash
./gradlew jar
./scripts/run-all-strategies.sh
./scripts/run-all-strategies.sh -m 100
./scripts/run-all-strategies.sh -w 10,110,210
./scripts/run-all-strategies.sh -a 3
./scripts/run-all-strategies.sh --skip-generate
```

Параметры `scripts/run-all-strategies.sh`:

- `-m, --message-count N` — число сообщений (по умолчанию `500`).
- `-w, --warm-variants L` — список warm-лимитов через запятую (`10,110,210`).
- `-a, --array-max-depth N` — глубина вложенности массивов (целое `>= 1`).
- `--skip-generate` — не генерировать `messages/`, использовать существующие.

## Стратегия парсинга

| Стратегия  | Описание                                                                 | Таблицы |
| ---------- | ------------------------------------------------------------------------ | ------- |
| `default`  | Main datasource + отдельный индексный datasource массивов variables      | 2       |

Подробнее: `strategies.md` и `docs/default_warm_arrays_design.md`.

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

Поддерживается только очистка parser datasource через `scripts/clean-druid-remote.sh`:

```bash
COORDINATOR_URL="http://192.168.1.27:8081" ./scripts/clean-druid-remote.sh
```

Параметры запуска:

```bash
# Рекомендуемый режим для новой стратегии
COORDINATOR_URL="http://192.168.1.27:8081" ./scripts/clean-druid-remote.sh --target default

# URL можно передать позиционно
./scripts/clean-druid-remote.sh --target default http://192.168.1.27:8081

# Для обратной совместимости поддерживается ENV-параметр
DRUID_CLEANUP_TARGET=default COORDINATOR_URL="http://192.168.1.27:8081" ./scripts/clean-druid-remote.sh
```

`--target`:

- `default` — очистка datasource новой стратегии (`default_process_default`, `default_process_variables_array_indexed`).
- `all` / `legacy` — оставлены только для обратной совместимости.

## Linux host runbook (новая стратегия)

Минимальный сценарий запуска на Linux host через актуальные скрипты:

```bash
# 1) Сборка JAR
./gradlew jar

# 2) Создание truststore для TLS (если Druid по HTTPS)
./scripts/create-druid-truststore.sh <host> <port> druid-truststore.p12 changeit
export DRUID_TRUST_STORE_PATH="$(pwd)/druid-truststore.p12"
export DRUID_TRUST_STORE_PASSWORD="changeit"
export DRUID_TRUST_STORE_TYPE="PKCS12"

# 3) Очистка datasource новой стратегии
COORDINATOR_URL="https://<coordinator-host>:<port>" ./scripts/clean-druid-remote.sh --target default

# 4) Полный прогон default pipeline
./scripts/run-all-strategies.sh -m 100
```

Примечания:

- `scripts/create-druid-truststore.sh` поддерживает режимы `local`, `chain`, `local+chain`, `auto` через `DRUID_TRUSTSTORE_MODE`.
- `scripts/run-all-strategies.sh` сам проверяет TLS и при необходимости может автоматически создать truststore.
- Для стабильного CI/host-прогона используйте `--target default` при очистке Druid.

## Устранение неполадок

- `Unsupported class file major version` -> запущена Java ниже 17.
- ingestion/query ошибки соединения -> проверьте `DRUID_*_URL`.
- `JAR not found` -> выполните `./gradlew jar` и проверьте `build/libs/`.
- ошибки SQL manifest -> `./gradlew verifyQueryManifest`.

