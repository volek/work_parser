# BPM Message Parser для Apache Druid

Kotlin-парсер BPM-сообщений для Apache Druid с поддержкой трёх стратегий хранения данных.

## 📋 Содержание

- [Обзор](#обзор)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Установка и настройка](#установка-и-настройка)
- [Использование](#использование)
- [Docker](#docker)
- [Архитектура](#архитектура)
- [Стратегии парсинга](#стратегии-парсинга)
- [SQL-запросы](#sql-запросы)
- [Устранение неполадок](#устранение-неполадок)

---

## Обзор

Проект предоставляет три стратегии хранения BPM-сообщений в Apache Druid:

| Стратегия | Описание | Таблицы |
|-----------|----------|---------|
| **Hybrid** | Flat columns + JSON blobs | 1 таблица |
| **EAV** | Entity-Attribute-Value | 2 таблицы |
| **Combined** | Tiered (Hot/Warm/Cold) | 2 таблицы |

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
├── messages/               # Generated test messages
├── query/                  # SQL queries for each strategy
│   ├── hybrid/             # 50+ queries for Hybrid
│   ├── eav/                # 50+ queries for EAV
│   └── combined/           # 50+ queries for Combined
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
| `DRUID_OVERLORD_URL` | `http://localhost:8090` | URL Druid Overlord |
| `DRUID_ROUTER_URL` | `http://localhost:8888` | URL Druid Router |
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
  connectTimeout: 30000
  readTimeout: 60000
  batchSize: 1000

fieldClassification:
  alwaysFlatten:
    - "processId"
    - "processName"
    - "status"
```

#### Файл .env

Для Docker используйте `.env` файл (скопируйте из `.env.example`):

```bash
cp .env.example .env
# Отредактируйте под ваше окружение
```

---

## Использование

### Команды CLI

```bash
# Показать справку
java -jar app.jar help

# Генерировать тестовые сообщения
java -jar app.jar generate [output-dir]

# Парсинг сообщений
java -jar app.jar parse <strategy> [input-dir]
# strategy: hybrid | eav | combined

# Парсинг и загрузка в Druid
java -jar app.jar parse <strategy> --ingest

# Выполнить SQL-запрос
java -jar app.jar query <query-file.sql>
```

### Примеры использования

```bash
# Генерация 25+ тестовых сообщений
docker compose run --rm bpm-parser generate messages

# Парсинг с Hybrid-стратегией
docker compose run --rm bpm-parser parse hybrid messages

# Парсинг с EAV-стратегией и загрузка в Druid
docker compose run --rm bpm-parser parse eav messages --ingest

# Выполнение запроса для поиска процессов по epkId
docker compose run --rm bpm-parser query query/hybrid/filtering/q13_filter_by_epkId.sql
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
│       └── CombinedRecord.kt
├── parser/
│   ├── MessageParser.kt    # JSON parsing
│   ├── VariableFlattener.kt  # Path extraction
│   └── strategy/
│       ├── ParseStrategy.kt   # Strategy interface
│       ├── HybridStrategy.kt
│       ├── EavStrategy.kt
│       └── CombinedStrategy.kt
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

**Таблица:** `process_hybrid`

Оптимальна для:
- Частых запросов по фиксированным полям
- Смешанных сценариев (hot + cold data)

```sql
SELECT process_name, var_epkId, var_caseId,
       JSON_VALUE(var_epkData_json, '$.epkEntity.ucpId')
FROM process_hybrid
WHERE state = 1 AND var_fio LIKE '%Иванов%'
```

### 2. EAV Strategy (Entity-Attribute-Value)

**Таблицы:** `process_events`, `process_variables`

Оптимальна для:
- Гибких запросов по любым переменным
- Анализа распределения переменных

```sql
SELECT pe.process_name, pv.var_value as epkId
FROM process_events pe
JOIN process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path = 'epkId'
```

### 3. Combined Strategy (Tiered)

**Таблицы:** `process_main`, `process_variables_indexed`

Оптимальна для:
- Баланса между производительностью и гибкостью
- Больших объёмов данных с разной частотой доступа

```sql
-- Tier 1: Hot columns (прямой доступ)
SELECT var_epkId, var_caseId FROM process_main

-- Tier 2: Indexed variables (через JOIN)
SELECT pm.*, pvi.var_value
FROM process_main pm
JOIN process_variables_indexed pvi 
  ON pm.process_id = pvi.process_id
WHERE pvi.var_category = 'epkData'

-- Tier 3: Cold blobs (JSON extraction)
SELECT JSON_VALUE(var_answerGFL_json, '$.results')
FROM process_main
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
└── combined/
    ├── tier_queries/   # По уровням
    └── mixed/          # Комбинированные
```

### Примеры запросов

```sql
-- Найти процессы за последние 24 часа
SELECT * FROM process_hybrid
WHERE start_date >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR

-- Топ-10 процессов по количеству
SELECT process_name, COUNT(*) as cnt
FROM process_hybrid
GROUP BY process_name
ORDER BY cnt DESC
LIMIT 10

-- Поиск по вложенным данным (EAV)
SELECT pe.process_id, pv.var_value
FROM process_events pe
JOIN process_variables pv ON pe.process_id = pv.process_id
WHERE pv.var_path LIKE 'epkData.epkEntity.%'
```

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
