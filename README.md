# BPM Message Parser для Apache Druid

Kotlin-парсер BPM-сообщений для Apache Druid с поддержкой пяти стратегий хранения данных.

## 📋 Содержание

- [Обзор](#обзор)
- [Требования](#требования)
- [Запуск на Linux-хосте (Java 17)](#linux-host)
- [Сборка на Windows для переноса на Linux](#windows-build-linux)
- [Быстрый старт](#быстрый-старт)
- [Установка и настройка](#установка-и-настройка)
- [Использование](#использование)
- [Пакетный запуск (все стратегии)](#пакетный-запуск-все-стратегии)
- [Docker](#docker)
- [Архитектура](#архитектура)
- [Стратегии парсинга](#стратегии-парсинга)
- [SQL-запросы](#sql-запросы)
- [Очистка данных](#очистка-данных)
- [Устранение неполадок](#устранение-неполадок)

---

## Обзор

Проект предоставляет пять стратегий хранения BPM-сообщений в Apache Druid:

| Стратегия | Описание | Таблицы |
|-----------|----------|---------|
| **Hybrid** | Flat columns + JSON blobs | 1 таблица |
| **EAV** | Entity-Attribute-Value | 2 таблицы |
| **Combined** | Tiered (Hot/Warm/Cold), с cold blob в Druid | 2 таблицы |
| **Compcom** | Compact combined: как Combined, но без cold blob в Druid | 2 таблицы |
| **Default** | Все поля сообщения как отдельные колонки (имена с точкой) | 1 таблица |

Для стратегий **Combined** и **Compcom** в `config.yaml` можно задать лимит warm-переменных на сообщение: `parser.warmVariablesLimit` (10..1010, шаг 100). Подробнее см. [strategies.md](strategies.md).

---

## Требования

### Для запуска в Docker (рекомендуется)

- **Docker** 24.0+
- **Docker Compose** v2.20+
- **Свободные ресурсы**: минимум 8 GB RAM, 4 CPU cores

### Для локальной разработки

- **JDK** 17+
- **Gradle** 8.x (опционально, есть wrapper)
- **Apache Druid** 28.0+ (опционально для тестов)

---

<a id="linux-host"></a>
## Запуск на Linux-хосте (Java 17)

Приложение собирается в один fat JAR и запускается на Linux любой архитектуры, поддерживаемой JVM, при установленной **Java 17** (JDK для сборки, JRE достаточно для запуска готового JAR).

<a id="windows-build-linux"></a>
### Сборка на Windows для переноса на Linux-хост

На **Windows** установите **JDK 17** (или используйте уже установленный), откройте **cmd** или **PowerShell** в корне репозитория (рядом с `gradlew.bat`):

```bat
gradlew.bat jar
gradlew.bat linuxHostBundle
```

| Команда | Результат |
|---------|-----------|
| `gradlew.bat jar` | Только fat JAR: `build\libs\bpm-druid-parser-1.0.0.jar` (версия из `build.gradle.kts`). |
| `gradlew.bat linuxHostBundle` | Архив **`build\distributions\bpm-druid-parser-1.0.0-linux-host.zip`**, в который включены: каталог **`scripts`** целиком (все скрипты из репозитория), дерево **`markdown`** со **всеми файлами `*.md`** проекта (сохранены относительные пути; исключены только каталоги сборки `build`, `.gradle` и `.git`), а также подкаталог **`libs`** с тем же fat JAR. |

На целевом Linux скопируйте ZIP, распакуйте (`unzip bpm-druid-parser-*-linux-host.zip -d parser-dist`), перейдите в каталог распаковки, выставьте права на shell-скрипты: `chmod +x scripts/*.sh`. Запуск приложения: `java -jar libs/bpm-druid-parser-1.0.0.jar help`. Для **`scripts/run-all-strategies.sh`** задайте путь к JAR: `export PARSER_JAR="$PWD/libs/bpm-druid-parser-1.0.0.jar"` (или скопируйте JAR в `build/libs/` и работайте из корня полного репозитория). Для сценариев из README дополнительно перенесите из клона репозитория каталоги **`query/`**, **`messages/`** (при необходимости), файл **`config.yaml`** и др. — они в архив **не входят**, в нём только JAR, скрипты и документация в Markdown.

### 1. Проверка Java

```bash
java -version   # ожидается 17.x
```

Если Java нет или другая версия, установите пакет с JDK/JRE 17 (пример для Debian/Ubuntu: `sudo apt install openjdk-17-jdk`, либо [Eclipse Temurin 17](https://adoptium.net/)).

### 2. Сборка из исходников на Linux

В корне репозитория:

```bash
chmod +x gradlew
./gradlew jar
```

Готовый артефакт: **`build/libs/bpm-druid-parser-1.0.0.jar`** (имя совпадает с `version` в `build.gradle.kts`).

Сборка на другой ОС (например, Windows или macOS) даёт тот же JAR — его можно скопировать на Linux-хост.

### 3. Что положить рядом с JAR

Запускайте из каталога, где есть нужные данные и конфигурация (или укажите пути в аргументах):

| Нужно | Назначение |
|-------|------------|
| `config.yaml` | Опционально; иначе — только переменные окружения и значения по умолчанию |
| `messages/` | Входные JSON для `parse` (по умолчанию) |
| `query/` | SQL-файлы для `query` и `query-suite` |
| `samples/` | По необходимости для сценариев с примерами |

Переменные окружения Druid те же, что в таблице в разделе [Установка и настройка](#установка-и-настройка) (`DRUID_BROKER_URL`, `DRUID_COORDINATOR_URL` и т.д.).

### 4. Примеры команд

```bash
# Справка
java -jar build/libs/bpm-druid-parser-1.0.0.jar help

# Генерация тестовых сообщений
java -jar build/libs/bpm-druid-parser-1.0.0.jar generate messages 100

# Парсинг (стратегия hybrid, вход из messages/)
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse hybrid messages

# Парсинг и загрузка в Druid
java -jar build/libs/bpm-druid-parser-1.0.0.jar parse hybrid messages --ingest

# SQL-запрос из файла
java -jar build/libs/bpm-druid-parser-1.0.0.jar query query/hybrid/q01_select_all.sql
```

Память JVM при необходимости: `java -Xms256m -Xmx512m -jar ...`.

### 5. Пакетный прогон на Linux (аналог `run-all-strategies.ps1`)

Скрипт **`scripts/run-all-strategies.sh`** выполняет тот же сценарий, что и PowerShell-версия, но **без Docker**: вызывает `java -jar` с fat JAR из `build/libs/` (см. переменную `PARSER_JAR`).

1. Очистка `logs/`, `query-results/`, `messages/`.
2. При заданном **`COORDINATOR_URL`** или **`DRUID_COORDINATOR_URL`** — вызов `scripts/clean-druid-remote.sh` для удаления datasource'ов парсера в Druid.
3. Генерация сообщений: `generate messages <N>`.
4. Для стратегий `combined` → `compcom` → `eav` → `hybrid` → `default`: `parse <strategy> messages --ingest` (для `combined`/`compcom` при `-w` — несколько прогонов с `PARSER_WARM_VARIABLES_LIMIT`).
5. Все `.sql` из `query/<strategy>/` → вывод в `query-results/<strategy>.txt`.

```bash
chmod +x scripts/run-all-strategies.sh
./gradlew jar   # если JAR ещё не собран

./scripts/run-all-strategies.sh
./scripts/run-all-strategies.sh -m 100
./scripts/run-all-strategies.sh -w 10,110,210
```

Переменные окружения: `PARSER_JAR`, `JAVA_CMD`, `JAVA_OPTS`, `COORDINATOR_URL` / `DRUID_COORDINATOR_URL` (см. комментарии в начале скрипта).

---

<a id="quick-start"></a>
## Быстрый старт

### Вариант 1: Standalone Druid на хосте

Если Druid уже установлен и запущен на вашей машине:

```bash
# 1. Скопируйте конфигурацию
cp .env.example .env

# 2. Настройте подключение (по умолчанию localhost:8888)
# Отредактируйте .env при необходимости

# 3. Запустите только парсер
docker compose up -d

# 4. Проверьте статус
docker compose ps
```

### Вариант 2: Druid в Docker (рекомендуется для разработки)

```bash
# 1. Запустите Druid кластер
docker compose -f docker-compose.druid.yml up -d

# 2. Дождитесь готовности (1-3 минуты)
docker compose -f docker-compose.druid.yml logs -f druid-router

# 3. Проверьте здоровье Druid
curl http://localhost:8888/status/health

# 4. Запустите парсер
docker compose up -d

# 5. Откройте Druid Console
# http://localhost:8888
```

### Вариант 3: Удалённый Druid кластер

```bash
# 1. Создайте .env с адресами кластера
cat > .env << EOF
DRUID_BROKER_URL=http://druid-prod.example.com:8082
DRUID_COORDINATOR_URL=http://druid-prod.example.com:8081
DRUID_OVERLORD_URL=http://druid-prod.example.com:8081
DRUID_ROUTER_URL=http://druid-prod.example.com:8888
EOF

# 2. Запустите парсер
docker compose up -d
```

### Работа с парсером

```bash
# Генерация тестовых сообщений
docker compose run --rm bpm-parser generate

# Парсинг с Hybrid-стратегией и загрузка в Druid
docker compose run --rm bpm-parser parse hybrid --ingest

# Выполнение SQL-запроса
docker compose run --rm bpm-parser query query/hybrid/q01_select_all.sql
```

---

## Установка и настройка

### Структура проекта

```
parser/
├── Dockerfile               # Multi-stage Docker build
├── docker-compose.yml       # Только парсер (подключается к внешнему Druid)
├── docker-compose.druid.yml # Druid кластер (отдельный запуск)
├── config.yaml              # Конфигурация приложения
├── .env.example             # Пример переменных окружения
├── build.gradle.kts         # Gradle build configuration
├── src/                     # Kotlin source code
│   ├── main/kotlin/ru/sber/parser/
│   │   ├── Application.kt         # Entry point
│   │   ├── config/                # Configuration classes
│   │   ├── model/                 # Domain models
│   │   ├── parser/                # Parsing strategies
│   │   ├── druid/                 # Druid client
│   │   └── generator/             # Message generator
│   └── test/               # Unit and integration tests
├── messages/               # Сгенерированные тестовые сообщения
├── logs/                   # Логи пакетных запусков (см. раздел «Пакетный запуск»)
├── query-results/          # Результаты скрипта «все запросы» (PowerShell, см. п. 4)
├── query/                  # SQL-запросы по стратегиям
│   ├── hybrid/             # 50+ queries for Hybrid
│   ├── eav/                # 50+ queries for EAV
│   ├── combined/           # 68 queries for Combined (combined_process_main + combined_process_variables_indexed)
│   ├── compcom/            # 70 queries for Compcom (compcom_process_main_compact + compcom_process_variables_indexed)
│   └── default/            # запросы для Default (все поля как колонки)
└── samples/                # Sample BPM messages
```

### Конфигурация

Приложение поддерживает конфигурацию через:
1. **Переменные окружения** (высший приоритет)
2. **config.yaml** (если файл существует)
3. **Значения по умолчанию**

#### Переменные окружения

| Переменная | Значение по умолчанию | Описание |
|------------|----------------------|----------|
| `DRUID_BROKER_URL` | `http://localhost:8082` | URL Druid Broker |
| `DRUID_COORDINATOR_URL` | `http://localhost:8081` | URL Druid Coordinator |
| `DRUID_OVERLORD_URL` | `http://localhost:8081` | URL Druid Overlord (в кластере часто 8090) |
| `DRUID_ROUTER_URL` | `http://localhost:8888` | URL Druid Router |
| `DRUID_USERNAME` | _(пусто)_ | Логин для Basic Auth (если требуется) |
| `DRUID_PASSWORD` | _(пусто)_ | Пароль для Basic Auth (если требуется) |
| `DRUID_CONNECT_TIMEOUT` | `30000` | Таймаут подключения (мс) |
| `DRUID_READ_TIMEOUT` | `60000` | Таймаут чтения (мс) |
| `DRUID_BATCH_SIZE` | `1000` | Размер пакета для ingestion |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | JVM опции |

#### Файл config.yaml

```yaml
druid:
  brokerUrl: "http://localhost:8082"
  coordinatorUrl: "http://localhost:8081"
  overlordUrl: "http://localhost:8090"
  routerUrl: "http://localhost:8888"
  # username: "my-user"
  # password: "my-password"
  connectTimeout: 30000
  readTimeout: 60000
  batchSize: 1000

fieldClassification:
  alwaysFlatten:
    - "processId"
    - "processName"
    - "status"

# Опционально: лимит warm-переменных для combined/compcom (10..1010, шаг 100)
# parser:
#   warmVariablesLimit: 110
```

#### Файл .env

Для Docker используйте `.env` файл (скопируйте из `.env.example`):

```bash
cp .env.example .env
# Отредактируйте под ваше окружение
```

---

## Использование

После `./gradlew jar` fat JAR лежит в `build/libs/bpm-druid-parser-1.0.0.jar` — подставьте этот путь вместо `app.jar` в примерах ниже. В Docker-образе JAR копируется как `app.jar`. Подробности для Linux без Docker: [Запуск на Linux-хосте (Java 17)](#linux-host).

### Команды CLI

```bash
# Показать справку
java -jar app.jar help

# Генерировать тестовые сообщения (по умолчанию 500 шт. в messages/)
java -jar app.jar generate [output-dir] [count]

# Парсинг сообщений
java -jar app.jar parse <strategy> [input-dir]
# strategy: hybrid | eav | combined | compcom | default

# Парсинг и загрузка в Druid
java -jar app.jar parse <strategy> --ingest

# Выполнить SQL-запрос
java -jar app.jar query <query-file.sql>
```

### Примеры использования

```bash
# Генерация 500 тестовых сообщений (по умолчанию)
docker compose run --rm bpm-parser generate
# Или: каталог и количество
docker compose run --rm bpm-parser generate messages 100

# Парсинг с Hybrid-стратегией
docker compose run --rm bpm-parser parse hybrid messages

# Парсинг с EAV-стратегией и загрузка в Druid
docker compose run --rm bpm-parser parse eav messages --ingest

# Выполнение запроса для поиска процессов по epkId
docker compose run --rm bpm-parser query query/hybrid/filtering/q13_filter_by_epkId.sql
```

---

## Пакетный запуск (все стратегии)

Ниже — команды для последовательного запуска генерации, парсинга, загрузки в Druid и выполнения всех SQL-запросов по каждой стратегии. Логи и вывод каждого шага пишутся в отдельные файлы по стратегиям в каталог `logs/`.

### Подготовка

```bash
# Создать каталог для логов (в корне проекта)
mkdir -p logs
```

В Windows (PowerShell):

```powershell
New-Item -ItemType Directory -Force -Path logs
```

### 1. Пакетная генерация сообщений

Генерация тестовых сообщений с сохранением лога в `logs/generate.log`:

```bash
docker compose run --rm bpm-parser generate messages 2>&1 | tee logs/generate.log
```

По умолчанию генерируется 500 сообщений в каталог `messages/`. Количество можно задать вторым аргументом: `generate messages 50`.

### 2. Парсинг по всем стратегиям

Парсинг одних и тех же сообщений (`messages/`) для каждой стратегии с записью логов в отдельные файлы:

```bash
# Hybrid
docker compose run --rm bpm-parser parse hybrid messages 2>&1 | tee logs/hybrid_parse.log

# EAV
docker compose run --rm bpm-parser parse eav messages 2>&1 | tee logs/eav_parse.log

# Combined
docker compose run --rm bpm-parser parse combined messages 2>&1 | tee logs/combined_parse.log

# Compcom (compact combined, без cold blob в Druid)
docker compose run --rm bpm-parser parse compcom messages 2>&1 | tee logs/compcom_parse.log

# Default
docker compose run --rm bpm-parser parse default messages 2>&1 | tee logs/default_parse.log
```

### 3. Загрузка в Druid по всем стратегиям

Парсинг и загрузка в Druid для каждой стратегии (парсинг + `--ingest`), логи — в отдельные файлы:

```bash
# Hybrid
docker compose run --rm bpm-parser parse hybrid messages --ingest 2>&1 | tee logs/hybrid_ingest.log

# EAV
docker compose run --rm bpm-parser parse eav messages --ingest 2>&1 | tee logs/eav_ingest.log

# Combined
docker compose run --rm bpm-parser parse combined messages --ingest 2>&1 | tee logs/combined_ingest.log

# Compcom
docker compose run --rm bpm-parser parse compcom messages --ingest 2>&1 | tee logs/compcom_ingest.log

# Default
docker compose run --rm bpm-parser parse default messages --ingest 2>&1 | tee logs/default_ingest.log
```

Перед запуском убедитесь, что Druid запущен и доступен (см. [Быстрый старт](#quick-start)).

### 4. Запуск всех SQL-запросов по стратегиям

Выполнение всех `.sql`-файлов по каждой стратегии с записью вывода в отдельный лог по стратегии:

```bash
# Hybrid — все запросы из query/hybrid/
for f in query/hybrid/*.sql; do
  echo "=== $f ===" >> logs/hybrid_queries.log
  docker compose run --rm bpm-parser query "$f" >> logs/hybrid_queries.log 2>&1
done

# EAV — все запросы из query/eav/
for f in query/eav/*.sql; do
  echo "=== $f ===" >> logs/eav_queries.log
  docker compose run --rm bpm-parser query "$f" >> logs/eav_queries.log 2>&1
done

# Combined — все запросы из query/combined/
for f in query/combined/*.sql; do
  echo "=== $f ===" >> logs/combined_queries.log
  docker compose run --rm bpm-parser query "$f" >> logs/combined_queries.log 2>&1
done

# Compcom — все запросы из query/compcom/
for f in query/compcom/*.sql; do
  echo "=== $f ===" >> logs/compcom_queries.log
  docker compose run --rm bpm-parser query "$f" >> logs/compcom_queries.log 2>&1
done

# Default — все запросы из query/default/
for f in query/default/*.sql; do
  echo "=== $f ===" >> logs/default_queries.log
  docker compose run --rm bpm-parser query "$f" >> logs/default_queries.log 2>&1
done
```

В Windows (PowerShell) для одной стратегии, например Hybrid:

```powershell
Get-ChildItem -Path query/hybrid -Filter *.sql | ForEach-Object {
  "=== $($_.FullName) ===" | Add-Content -Path logs/hybrid_queries.log
  docker compose run --rm bpm-parser query $_.FullName >> logs/hybrid_queries.log 2>&1
}
```

Аналогично замените `query/hybrid` на `query/eav`, `query/combined`, `query/compcom` или `query/default` и имена лог-файлов на `eav_queries.log`, `combined_queries.log`, `compcom_queries.log` или `default_queries.log`.

#### Полный цикл в PowerShell (генерация → загрузка в Druid → запросы)

Скрипт `scripts/run-all-strategies.ps1` выполняет по очереди: генерацию сообщений, парсинг и загрузку в Druid по каждой стратегии, затем все SQL-запросы. Запуск из корня проекта:

```powershell
.\scripts\run-all-strategies.ps1
```

Количество сообщений (по умолчанию 500):

```powershell
.\scripts\run-all-strategies.ps1 -MessageCount 100
```

Варианты лимита warm-колонок для стратегий **combined** и **compcom** (10..1010, шаг 100): несколько прогонов с разными `PARSER_WARM_VARIABLES_LIMIT`, логи `*_ingest_warm{N}.log`:

```powershell
.\scripts\run-all-strategies.ps1 -WarmLimitVariants 10, 110, 210
```

Перед запуском убедитесь, что Druid запущен и доступен (см. [Быстрый старт](#quick-start)).

#### Только запросы по всем стратегиям (Windows PowerShell)

Если сообщения уже сгенерированы и загружены в Druid, можно выполнить только SQL-запросы. Скрипт пишет результаты в `query-results/<strategy>.txt`. Запуск из корня проекта:

```powershell
$strategies = @("combined", "compcom", "eav", "hybrid", "default")
$outDir = "query-results"
$root = (Get-Location).Path

New-Item -ItemType Directory -Path $outDir -Force | Out-Null

foreach ($s in $strategies) {
    $queryPath = Join-Path $root "query" $s
    if (-not (Test-Path $queryPath)) { Write-Host "Пропуск $s: нет каталога $queryPath"; continue }

    $outFile = Join-Path $outDir "$s.txt"
    Set-Content -Path $outFile -Value "=== Strategy: $s ===`n"

    Get-ChildItem -Path $queryPath -Recurse -Filter *.sql | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($root.Length).TrimStart('\','/').Replace('\','/')
        Add-Content -Path $outFile -Value "`n----- $rel -----"
        docker compose run --rm bpm-parser query $rel 2>&1 | Add-Content -Path $outFile
    }
    Write-Host "Готово: $outFile"
}
```

### Структура логов после пакетного запуска

| Файл | Содержимое |
|------|-------------|
| `logs/generate.log` | Вывод генерации тестовых сообщений |
| `logs/hybrid_parse.log` | Парсинг стратегии Hybrid |
| `logs/eav_parse.log` | Парсинг стратегии EAV |
| `logs/combined_parse.log` | Парсинг стратегии Combined |
| `logs/compcom_parse.log` | Парсинг стратегии Compcom |
| `logs/hybrid_ingest.log` | Загрузка в Druid (Hybrid) |
| `logs/eav_ingest.log` | Загрузка в Druid (EAV) |
| `logs/combined_ingest.log` | Загрузка в Druid (Combined) |
| `logs/compcom_ingest.log` | Загрузка в Druid (Compcom) |
| `logs/hybrid_queries.log` | Результаты всех SQL-запросов для Hybrid |
| `logs/eav_queries.log` | Результаты всех SQL-запросов для EAV |
| `logs/combined_queries.log` | Результаты всех SQL-запросов для Combined |
| `logs/compcom_queries.log` | Результаты всех SQL-запросов для Compcom |
| `logs/default_parse.log` | Парсинг стратегии Default |
| `logs/default_ingest.log` | Загрузка в Druid (Default) |
| `logs/default_queries.log` | Результаты всех SQL-запросов для Default |
| `query-results/combined.txt` | Вывод скрипта PowerShell (все запросы Combined) |
| `query-results/compcom.txt` | Вывод скрипта PowerShell (все запросы Compcom) |
| `query-results/eav.txt` | Вывод скрипта PowerShell (все запросы EAV) |
| `query-results/hybrid.txt` | Вывод скрипта PowerShell (все запросы Hybrid) |
| `query-results/default.txt` | Вывод скрипта PowerShell (все запросы Default) |

Полный цикл (генерация → загрузка в Druid → запросы по всем стратегиям) одной командой в **Bash** (Linux, macOS, Git Bash или WSL на Windows):

```bash
mkdir -p logs
docker compose run --rm bpm-parser generate messages 2>&1 | tee logs/generate.log
for strategy in hybrid eav combined compcom default; do
  docker compose run --rm bpm-parser parse $strategy messages --ingest 2>&1 | tee logs/${strategy}_ingest.log
  for f in query/$strategy/*.sql; do
    echo "=== $f ===" >> logs/${strategy}_queries.log
    docker compose run --rm bpm-parser query "$f" >> logs/${strategy}_queries.log 2>&1
  done
done
```

---

## Docker

### Архитектура развёртывания

Проект разделён на два compose-файла:
- **docker-compose.yml** — только BPM Parser (подключается к внешнему Druid)
- **docker-compose.druid.yml** — Druid кластер (запускается отдельно)

Это позволяет:
- Использовать standalone Druid на хосте
- Подключаться к существующему Druid кластеру
- Запускать Druid и Parser независимо друг от друга

### Варианты запуска Druid

#### Вариант 1: Standalone Druid на хосте

Если Druid установлен локально (не в Docker):

```bash
# Запустите Druid (из директории Druid)
./bin/start-single-server-medium

# Запустите парсер
docker compose up -d
```

По умолчанию парсер использует `host.docker.internal` для доступа к localhost хоста.

#### Вариант 2: Druid кластер в Docker

```bash
# Запуск полного кластера Druid
docker compose -f docker-compose.druid.yml up -d

# Дождитесь готовности (2-5 минут)
docker compose -f docker-compose.druid.yml ps

# Компоненты: postgres, zookeeper, coordinator, overlord, broker, historical, middlemanager, router
```

#### Вариант 3: Druid standalone в Docker

Запускает все компоненты Druid в одном контейнере — удобно для разработки:

```bash
# Запуск standalone режима через профиль
docker compose -f docker-compose.druid.yml --profile standalone up -d druid-standalone postgres zookeeper
```

### Запуск парсера

```bash
# Создайте .env (опционально)
cp .env.example .env

# Запустите парсер
docker compose up -d

# Проверьте статус
docker compose ps
```

### Совместный запуск с Druid в Docker

Если Druid запущен через `docker-compose.druid.yml`, настройте `.env`:

```bash
# .env для подключения к Druid в Docker
DRUID_BROKER_URL=http://druid-broker:8082
DRUID_COORDINATOR_URL=http://druid-coordinator:8081
DRUID_OVERLORD_URL=http://druid-overlord:8090
DRUID_ROUTER_URL=http://druid-router:8888
```

И подключите парсер к сети Druid:

```bash
# Запуск парсера в сети Druid
docker compose up -d
docker network connect docker-compose-druid_druid-network bpm-parser
```

### Команды Docker Compose

```bash
# === Druid ===
# Запуск Druid кластера
docker compose -f docker-compose.druid.yml up -d

# Логи Druid
docker compose -f docker-compose.druid.yml logs -f druid-router

# Остановка Druid
docker compose -f docker-compose.druid.yml down

# Очистка Druid (с данными)
docker compose -f docker-compose.druid.yml down -v

# === Parser ===
# Запуск парсера
docker compose up -d

# Логи парсера
docker compose logs -f bpm-parser

# Остановка парсера
docker compose down

# Пересборка парсера
docker compose build --no-cache bpm-parser
```

### Порты сервисов

| Сервис | Порт | Описание |
|--------|------|----------|
| Druid Router (Console) | 8888 | Web UI консоль Druid |
| Druid Coordinator | 8081 | Координатор кластера |
| Druid Overlord | 8090 | Управление задачами |
| Druid Broker | 8082 | SQL-запросы |
| Druid Historical | 8083 | Исторические данные |
| Druid MiddleManager | 8091 | Индексация |
| Peon Tasks | 8100-8105 | Задачи индексации |
| PostgreSQL | - | Внутренняя сеть |
| ZooKeeper | - | Внутренняя сеть |

### Druid Standalone vs Cluster

| Характеристика | Standalone | Cluster |
|----------------|------------|---------|
| Контейнеров | 3 (pg, zk, druid) | 9 |
| RAM минимум | 6 GB | 8 GB |
| Время старта | 1-2 мин | 2-5 мин |
| Использование | Разработка | Production |
| Масштабирование | Нет | Да |

### Проверка готовности

```bash
# Все сервисы должны быть в статусе healthy
docker compose ps

# Или проверьте конкретный сервис
curl http://localhost:8888/status/health
```

---

## Архитектура

### Компоненты Docker Stack

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Network                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │ PostgreSQL  │    │  ZooKeeper  │    │ BPM Parser  │         │
│  │  (metadata) │    │ (coordination│    │  (Kotlin)   │         │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘         │
│         │                  │                  │                 │
│         └──────────┬───────┴──────────────────┘                 │
│                    │                                            │
│  ┌─────────────────┴─────────────────────────────────────┐     │
│  │                   Apache Druid Cluster                 │     │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐       │     │
│  │  │Coordinator │  │  Overlord  │  │   Router   │──:8888│     │
│  │  └────────────┘  └────────────┘  └────────────┘       │     │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐       │     │
│  │  │   Broker   │  │ Historical │  │MiddleManager│      │     │
│  │  └────────────┘  └────────────┘  └────────────┘       │     │
│  └───────────────────────────────────────────────────────┘     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Архитектура приложения

```
ru.sber.parser/
├── Application.kt          # CLI entry point
├── config/
│   ├── AppConfig.kt        # Configuration (env support)
│   └── FieldClassification.kt  # Tier 1/2/3 field config
├── model/
│   ├── BpmMessage.kt       # Domain models
│   ├── NodeInstance.kt
│   └── druid/
│       ├── HybridRecord.kt
│       ├── EavRecord.kt
│       ├── CombinedRecord.kt
│       └── DefaultRecord.kt
├── parser/
│   ├── MessageParser.kt    # JSON parsing
│   ├── VariableFlattener.kt  # Path extraction
│   └── strategy/
│       ├── ParseStrategy.kt   # Strategy interface
│       ├── HybridStrategy.kt
│       ├── EavStrategy.kt
│       ├── CombinedStrategy.kt
│       ├── CompcomStrategy.kt # Compact combined (no cold blob)
│       └── DefaultStrategy.kt
├── druid/
│   ├── DruidClient.kt      # HTTP client
│   ├── IngestionSpec.kt    # Ingestion specs
│   └── SchemaGenerator.kt  # Dynamic schema
└── generator/
    ├── MessageGenerator.kt # Test data generator
    └── GeneratorRunner.kt
```

---

## Стратегии парсинга

### 1. Hybrid Strategy (Flat + JSON)

**Таблица:** `hybrid_process_hybrid`

Оптимальна для:
- Частых запросов по фиксированным полям
- Смешанных сценариев (hot + cold data)

```sql
SELECT process_name, var_epkId, var_caseId,
       JSON_VALUE(var_epkData_json, '$.epkEntity.ucpId')
FROM hybrid_process_hybrid
WHERE state = 1 AND var_fio LIKE '%Иванов%'
```

### 2. EAV Strategy (Entity-Attribute-Value)

**Таблицы:** `eav_process_events`, `eav_process_variables`

Оптимальна для:
- Гибких запросов по любым переменным
- Анализа распределения переменных

```sql
SELECT pe.process_name, pv.var_value as epkId
FROM eav_process_events pe
JOIN eav_process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path = 'epkId'
```

### 3. Combined Strategy (Tiered)

**Таблицы:** `combined_process_main`, `combined_process_variables_indexed`

Оптимальна для:
- Баланса между производительностью и гибкостью
- Больших объёмов данных с разной частотой доступа

```sql
-- Tier 1: Hot columns (прямой доступ)
SELECT var_epkId, var_caseId FROM combined_process_main

-- Tier 2: Indexed variables (через JOIN)
SELECT pm.*, pvi.var_value
FROM combined_process_main pm
JOIN combined_process_variables_indexed pvi 
  ON pm.process_id = pvi.process_id
WHERE pvi.var_category = 'epkData'

-- Tier 3: Cold blobs (JSON extraction) — только в combined, в compcom колонки var_blob_json нет
SELECT JSON_VALUE(var_blob_json, '$.answerGFL')
FROM combined_process_main
```

При задании в `config.yaml` секции `parser.warmVariablesLimit` (10..1010, шаг 100) число записей в `combined_process_variables_indexed`/`compcom_process_variables_indexed` на одно сообщение ограничивается (для тестов и вариативности объёма warm-переменных). Подробнее: [strategies.md](strategies.md).

### 4. Compcom Strategy (compact combined, без cold blob)

**Таблицы:** `compcom_process_main_compact`, `compcom_process_variables_indexed`

Как Combined, но **без сохранения cold blob в Druid**: в основной таблице нет колонки `var_blob_json`. Поддерживается тот же лимит warm-переменных (`parser.warmVariablesLimit`).

Оптимальна для:
- Сценариев, где холодные переменные в Druid не нужны (экономия места)
- Тестов и сравнения с combined при одинаковом наборе warm-переменных

```sql
SELECT process_id, process_name, var_epkId, var_caseId
FROM compcom_process_main_compact
WHERE state = 1
LIMIT 100
```

### 5. Default Strategy (все поля как колонки)

**Таблица:** `default_process_default`

Парсит все поля входящего сообщения и сохраняет их в Druid в виде отдельных колонок. Имена колонок для переменных формируются из путей с разделителем «точка» (например, `variables.caseId`, `variables.staticData.clientEpkId`). В SQL идентификаторы с точкой указываются в двойных кавычках.

Оптимальна для:
- Полного отображения сырых данных без предзаданной схемы
- Аналитики по произвольным полям без переиндексации

```sql
-- Все колонки
SELECT * FROM default_process_default ORDER BY __time DESC LIMIT 100

-- Колонки с точкой в имени — в кавычках
SELECT id, process_name, "variables.caseId", "variables.staticData.caseId"
FROM default_process_default
WHERE state = 2
```

---

## SQL-запросы

Проект содержит 150+ готовых SQL-запросов:

```
query/
├── hybrid/
│   ├── basic/          # Базовые SELECT
│   ├── filtering/      # WHERE фильтры
│   ├── json_access/    # JSON функции
│   ├── aggregations/   # GROUP BY, COUNT
│   └── complex/        # Подзапросы, CASE
├── eav/
│   ├── basic/
│   ├── joins/          # JOIN таблиц
│   └── aggregations/
├── combined/           # 68 запросов (combined_process_main + combined_process_variables_indexed)
├── compcom/            # 70 запросов (compcom_process_main_compact + compcom_process_variables_indexed)
└── default/            # Default: все поля как колонки
```

### Примеры запросов

```sql
-- Найти процессы за последние 24 часа
SELECT * FROM hybrid_process_hybrid
WHERE start_date >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR

-- Топ-10 процессов по количеству
SELECT process_name, COUNT(*) as cnt
FROM hybrid_process_hybrid
GROUP BY process_name
ORDER BY cnt DESC
LIMIT 10

-- Поиск по вложенным данным (EAV)
SELECT pe.process_id, pv.var_value
FROM eav_process_events pe
JOIN eav_process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path LIKE 'epkData.epkEntity.%'
```

---

## Очистка данных

Удаление данных от предыдущего запуска (сгенерированные сообщения, логи, артефакты сборки, при необходимости — тома Druid):

**Windows (PowerShell):**
```powershell
# Очистка парсера (messages, logs, build, том parser-logs)
.\scripts\clean-run-data.ps1

# Полный сброс, включая остановку Druid и удаление всех томов
.\scripts\clean-run-data.ps1 -FullReset
```

**Linux / macOS / WSL (Bash):**
```bash
# Очистка парсера
./scripts/clean-run-data.sh

# Полный сброс (Druid + все тома)
./scripts/clean-run-data.sh --full
```

Одной командой без скрипта (только парсер: контейнеры + том логов, без удаления `messages/` и `build/`):
```bash
docker compose down -v
```

### Очистка данных в Druid на отдельном хосте

Если Druid запущен на другом сервере (не в `docker-compose` этого проекта), данные парсера хранятся в **datasource'ах** Druid. Их можно удалить через HTTP API Coordinator'а.

**Имена datasource'ов, создаваемых парсером:**

| Стратегия  | Datasource'ы |
|------------|----------------|
| Hybrid     | `hybrid_process_hybrid` |
| EAV        | `eav_process_events`, `eav_process_variables` |
| Combined   | `combined_process_main`, `combined_process_variables_indexed` |
| Compcom    | `compcom_process_main_compact`, `compcom_process_variables_indexed` |
| Default    | `default_process_default` |

**Требования:** нужен URL **Coordinator** (порт 8081), не Router. В `.env` это `DRUID_COORDINATOR_URL`. Пример: Coordinator на отдельном хосте `192.168.1.27` → `http://192.168.1.27:8081`.

**Список всех datasource'ов в кластере:**
```bash
curl -s "http://192.168.1.27:8081/druid/coordinator/v1/datasources"
```

**Удаление одного datasource** (безвозвратно удаляет все данные в нём):
```bash
COORDINATOR_URL="http://192.168.1.27:8081"

curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/hybrid_process_hybrid"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/eav_process_events"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/eav_process_variables"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/combined_process_main"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/combined_process_variables_indexed"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/compcom_process_main_compact"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/compcom_process_variables_indexed"
curl -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/default_process_default"
```

Удаляйте только те datasource'ы, которые реально созданы парсером (лишние запросы вернут 404).

**Скрипты** для удаления всех datasource'ов парсера на указанном Coordinator (пример: хост `192.168.1.27`, порт 8081):

```bash
# Bash (Linux / macOS / WSL)
COORDINATOR_URL="http://192.168.1.27:8081" ./scripts/clean-druid-remote.sh
# или
./scripts/clean-druid-remote.sh http://192.168.1.27:8081
```

```powershell
# PowerShell (Windows)
$env:COORDINATOR_URL = "http://192.168.1.27:8081"; .\scripts\clean-druid-remote.ps1
# или
.\scripts\clean-druid-remote.ps1 -CoordinatorUrl "http://192.168.1.27:8081"
```

Скрипты удаляют только перечисленные выше имена; если datasource нет в кластере, запрос пропускается (404).

---

## Устранение неполадок

### Сервисы не запускаются

```bash
# Проверьте доступные ресурсы
docker system df

# Освободите место
docker system prune -a

# Проверьте логи конкретного сервиса
docker compose logs druid-coordinator
```

### Druid долго запускается

Первый запуск может занять 2-5 минут. Проверьте статус:

```bash
# Все сервисы должны быть healthy
docker compose ps

# Проверьте healthcheck
curl http://localhost:8888/status/health
```

### Ошибки подключения к Druid

```bash
# Проверьте, что Router доступен
curl http://localhost:8888/status/health

# Проверьте сетевое подключение контейнера
docker compose exec bpm-parser ping druid-router
```

### Недостаточно памяти

Для Druid кластера рекомендуется минимум 8 GB RAM. Уменьшите потребление:

```yaml
# В docker-compose.yml для сервисов druid-*
environment:
  druid_indexer_runner_javaOptsArray: '["-Xmx512m"]'
```

### Пересборка образа

```bash
# При изменении кода
docker compose build --no-cache bpm-parser
docker compose up -d bpm-parser
```

---

## Лицензия

Внутренний проект Sber BPM Team.

---

## Контакты

При возникновении вопросов обращайтесь к команде разработки BPM.
