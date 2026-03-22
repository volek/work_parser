# Развёртывание на Linux-хосте (Java 17)

Документ описывает состав ZIP-сборки **`linux-host`** и как запускать приложение без Gradle на целевой машине с **Java 17**.

## Требования

- **JRE или JDK 17** (для запуска достаточно JRE). Проверка: `java -version` → строка с `17`.
- Доступ к **Apache Druid** (если используете загрузку данных и SQL), либо только локальные команды (`help`, `generate` и т.д.).

Установка OpenJDK 17 (пример Debian/Ubuntu):

```bash
sudo apt update && sudo apt install -y openjdk-17-jre-headless
java -version
```

---

## Состав архива

После распаковки `bpm-druid-parser-*-linux-host.zip` в каталоге (условно `parser-dist/`) лежит следующая структура:

| Путь | Назначение |
|------|------------|
| **`DEPLOYMENT.md`** | Краткая инструкция по развёртыванию (этот файл). |
| **`config.yaml`** | Основной файл конфигурации (URL Druid, таймауты, параметры парсера). Переменные окружения имеют приоритет над файлом. |
| **`libs/`** | Fat JAR приложения: `bpm-druid-parser-<версия>.jar` (все зависимости внутри). |
| **`scripts/`** | Вспомогательные сценарии: пакетный прогон стратегий, очистка данных, утилиты. |
| **`query/`** | Дерево SQL-файлов по стратегиям (`combined`, `compcom`, `eav`, `hybrid`, `default` и др.) — для команд `query` / `query-suite`. |
| **`messages/`** | JSON-сообщения для сценариев парсинга и тестов (входные данные по умолчанию для `parse`). |
| **`markdown/`** | Копии всех `*.md` из исходного проекта (документация, сохранены относительные пути; исключены каталоги сборки). |

Рабочий каталог для команд — **корень распаковки** (там же должны лежать `config.yaml`, при необходимости — `messages/`, `query/`).

---

## Быстрый старт

1. Скопируйте ZIP на сервер и распакуйте:

   ```bash
   unzip bpm-druid-parser-*-linux-host.zip -d parser-dist
   cd parser-dist
   ```

2. Выдайте права на выполнение shell-скриптов:

   ```bash
   chmod +x scripts/*.sh
   ```

3. Проверка JAR и справка:

   ```bash
   java -jar libs/bpm-druid-parser-1.0.0.jar help
   ```

   Имя JAR совпадает с версией в сборке; при смене версии подставьте актуальное имя из `libs/`.

---

## Переменные окружения (Druid)

При необходимости переопределите URL компонентов Druid (см. также комментарии в `config.yaml`):

| Переменная | Пример |
|------------|--------|
| `DRUID_BROKER_URL` | `http://broker:8082` |
| `DRUID_COORDINATOR_URL` | `http://coordinator:8081` |
| `DRUID_OVERLORD_URL` | `http://overlord:8090` |
| `DRUID_ROUTER_URL` | `http://router:8888` |

Пример одноразового запуска:

```bash
export DRUID_BROKER_URL="http://10.0.0.5:8082"
export DRUID_COORDINATOR_URL="http://10.0.0.5:8081"
java -jar libs/bpm-druid-parser-1.0.0.jar help
```

---

## Примеры команд

Запуск из **корня распаковки** (рядом с `config.yaml`):

```bash
# Справка
java -jar libs/bpm-druid-parser-1.0.0.jar help

# Генерация тестовых сообщений в каталог messages/
java -jar libs/bpm-druid-parser-1.0.0.jar generate messages 100

# Парсинг и загрузка (пример стратегии hybrid)
java -jar libs/bpm-druid-parser-1.0.0.jar parse hybrid messages --ingest

# Выполнение одного SQL-файла из дерева query/
java -jar libs/bpm-druid-parser-1.0.0.jar query query/hybrid/q01_select_all.sql
```

Путь к JAR можно задать явно:

```bash
export PARSER_JAR="$PWD/libs/bpm-druid-parser-1.0.0.jar"
```

---

## Пакетный сценарий: все стратегии

Скрипт **`scripts/run-all-strategies.sh`** выполняет полный цикл: очистка логов/результатов, при необходимости очистка datasource в Druid, генерация сообщений, парсинг по всем стратегиям, прогон SQL из `query/<стратегия>/`.

Из корня распаковки:

```bash
chmod +x scripts/run-all-strategies.sh
./scripts/run-all-strategies.sh
```

Опции: `-m N` — число сообщений (по умолчанию 500); `-w 10,110,210` — варианты warm-лимита для combined/compcom.

Переменные: `PARSER_JAR` (по умолчанию указывает на `libs/bpm-druid-parser-1.0.0.jar` в этом каталоге), `JAVA_CMD`, `JAVA_OPTS`, `COORDINATOR_URL` / `DRUID_COORDINATOR_URL` для удалённой очистки Druid.

Подробнее см. **`markdown/README.md`** внутри архива (разделы про Linux-хост и пакетный запуск).

---

## Устранение неполадок

- **`java: command not found`** — не установлена Java или не в `PATH`; установите JRE 17.
- **`Unsupported class file major version`** — используется Java ниже 17; переключите `java` на 17 (`update-alternatives` или полный путь к `java`).
- **`JAR не найден`** — проверьте `libs/` и имя файла; задайте `PARSER_JAR` вручную.
- **Druid недоступен** — проверьте сеть, firewall и значения URL в `config.yaml` / переменных окружения.
