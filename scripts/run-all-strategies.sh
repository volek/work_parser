#!/usr/bin/env bash
# =============================================================================
# Полный цикл на Linux-хосте под Java 17 (без Docker): как run-all-strategies.ps1
# =============================================================================
# Запуск из корня проекта (рядом с build.gradle.kts, query/, config.yaml):
#   chmod +x scripts/run-all-strategies.sh
#   ./scripts/run-all-strategies.sh
#
# Количество сообщений (по умолчанию 500):
#   ./scripts/run-all-strategies.sh -m 100
#
# Варианты warm-лимита для combined/compcom (10..1010, шаг 100), несколько прогонов:
#   ./scripts/run-all-strategies.sh -w 10,110,210
#
# Пропуск генерации messages (использовать уже существующие файлы в messages/):
#   ./scripts/run-all-strategies.sh --skip-generate
#
# Переменные окружения:
#   PARSER_JAR      — путь к fat JAR (по умолчанию build/libs/bpm-druid-parser-1.0.0.jar)
#   JAVA_CMD        — бинарник JVM (по умолчанию java)
#   JAVA_OPTS       — опции JVM, напр. "-Xms256m -Xmx512m"
#   COORDINATOR_URL или DRUID_COORDINATOR_URL — для очистки datasource'ов через scripts/clean-druid-remote.sh
#
# Требуется: Java 17+, собранный JAR (./gradlew jar), доступный Druid при --ingest и query.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# Репозиторий с Gradle: JAR в build/libs/. Распакованный linux-host.zip: JAR в libs/.
if [[ -f "$ROOT/build.gradle.kts" ]]; then
  DEFAULT_JAR="$ROOT/build/libs/bpm-druid-parser-1.0.0.jar"
else
  DEFAULT_JAR="$ROOT/libs/bpm-druid-parser-1.0.0.jar"
fi
JAR="${PARSER_JAR:-$DEFAULT_JAR}"
JAVA_BIN="${JAVA_CMD:-java}"
MESSAGE_COUNT=500
WARM_VARIANTS=()
SKIP_GENERATE=false

usage() {
  cat <<EOF
Использование: $0 [опции]

  -m, --message-count N   Число генерируемых сообщений (по умолчанию 500)
  -w, --warm-variants L   Список лимитов warm через запятую для combined/compcom (напр. 10,110,210)
      --skip-generate      Пропустить этап generate messages
  -h, --help              Эта справка

Переменные: PARSER_JAR, JAVA_CMD, JAVA_OPTS, COORDINATOR_URL / DRUID_COORDINATOR_URL
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -m|--message-count)
      MESSAGE_COUNT="${2:?}"
      shift 2
      ;;
    -w|--warm-variants)
      IFS=',' read -r -a WARM_VARIANTS <<< "${2:?}"
      shift 2
      ;;
    --skip-generate)
      SKIP_GENERATE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Неизвестный аргумент: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -f "$JAR" ]]; then
  echo "JAR не найден: $JAR" >&2
  echo "Соберите проект: ./gradlew jar" >&2
  exit 1
fi

run_jar() {
  # shellcheck disable=SC2086
  "$JAVA_BIN" ${JAVA_OPTS:-} -jar "$JAR" "$@"
}

LOGS_DIR="$ROOT/logs"
OUT_DIR="$ROOT/query-results"
MESSAGES_DIR="$ROOT/messages"
CONFIG_FILE="$ROOT/config.yaml"

needs_tls_truststore() {
  # Проверяем, есть ли вообще https:// в конфиге/ENV.
  # Если есть — без truststore JVM, скорее всего, упадёт с SunCertPathBuilderException.
  if [[ -n "${DRUID_ROUTER_URL:-}" && "${DRUID_ROUTER_URL:-}" == https://* ]]; then return 0; fi
  if [[ -n "${DRUID_BROKER_URL:-}" && "${DRUID_BROKER_URL:-}" == https://* ]]; then return 0; fi
  if [[ -n "${DRUID_COORDINATOR_URL:-}" && "${DRUID_COORDINATOR_URL:-}" == https://* ]]; then return 0; fi
  if [[ -n "${DRUID_OVERLORD_URL:-}" && "${DRUID_OVERLORD_URL:-}" == https://* ]]; then return 0; fi
  if [[ -f "$CONFIG_FILE" ]] && grep -q "https://" "$CONFIG_FILE"; then return 0; fi
  return 1
}

first_https_url() {
  # Пытаемся взять URL из ENV (приоритет), иначе — из config.yaml (первый https://).
  for v in DRUID_ROUTER_URL DRUID_OVERLORD_URL DRUID_COORDINATOR_URL DRUID_BROKER_URL; do
    local val="${!v:-}"
    if [[ -n "$val" && "$val" == https://* ]]; then
      echo "$val"
      return 0
    fi
  done
  if [[ -f "$CONFIG_FILE" ]]; then
    # Простейшее извлечение: берём первую строку, где встречается https:// и есть URL.
    local line
    line="$(grep -m 1 "https://" "$CONFIG_FILE" || true)"
    if [[ -n "$line" ]]; then
      # выдёргиваем то, что похоже на URL, без кавычек
      echo "$line" | sed -E 's/.*(https:\/\/[^"'\''[:space:]]+).*/\1/'
      return 0
    fi
  fi
  return 1
}

parse_host_port_from_https_url() {
  # Вход: https://host:port/...
  # Выход: "host port" (port может быть 443, если не указан)
  local url="$1"
  local rest="${url#https://}"
  local authority="${rest%%/*}"
  local host="$authority"
  local port="443"

  # IPv6 в [] тут не обрабатываем — если будет нужно, добавим.
  if [[ "$authority" == *:* ]]; then
    host="${authority%%:*}"
    port="${authority##*:}"
  fi
  echo "$host $port"
}

normalize_bool() {
  local v="${1:-}"
  v="$(echo "$v" | tr '[:upper:]' '[:lower:]' | xargs)"
  [[ "$v" == "true" || "$v" == "1" || "$v" == "yes" ]]
}

read_config_coordinator_url() {
  local cfg="$1"
  [[ -f "$cfg" ]] || return 0

  awk '
    /^[[:space:]]*druid:[[:space:]]*$/ { in_druid=1; next }
    in_druid && /^[^[:space:]]/ { in_druid=0 }
    in_druid && /^[[:space:]]*coordinatorUrl:[[:space:]]*/ {
      line=$0
      sub(/^[[:space:]]*coordinatorUrl:[[:space:]]*/, "", line)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line ~ /^".*"$/ || line ~ /^'\''.*'\''$/) {
        line=substr(line, 2, length(line)-2)
      }
      print line
      exit
    }
  ' "$cfg"
}

echo "=== Очистка артефактов: logs, query-results, messages ==="
mkdir -p "$LOGS_DIR" "$OUT_DIR" "$MESSAGES_DIR"
rm -rf "${LOGS_DIR:?}/"* 2>/dev/null || true
rm -rf "${OUT_DIR:?}/"* 2>/dev/null || true
rm -rf "${MESSAGES_DIR:?}/"* 2>/dev/null || true

CONFIG_COORD="$(read_config_coordinator_url "$CONFIG_FILE")"
COORD="${COORDINATOR_URL:-${DRUID_COORDINATOR_URL:-${CONFIG_COORD:-}}}"
if [[ -n "$COORD" ]]; then
  echo "=== Очистка datasource'ов в Druid (Coordinator: $COORD) ==="
  COORDINATOR_URL="$COORD" "$ROOT/scripts/clean-druid-remote.sh" || {
    echo "Предупреждение: clean-druid-remote.sh завершился с ошибкой, продолжаем." >&2
  }
else
  echo "Пропуск очистки Druid: не заданы COORDINATOR_URL / DRUID_COORDINATOR_URL и не найден druid.coordinatorUrl в config.yaml."
fi

if [[ -f "$CONFIG_FILE" ]]; then
  export PARSER_CONFIG_PATH="$CONFIG_FILE"
  echo "=== PARSER_CONFIG_PATH: $PARSER_CONFIG_PATH ==="
else
  echo "Предупреждение: config.yaml не найден по пути $CONFIG_FILE; приложение будет использовать ENV/defaults." >&2
fi

# ---------------------------------------------------------------------------
# TLS truststore (для https:// Druid endpoint'ов)
# ---------------------------------------------------------------------------
if needs_tls_truststore; then
  insecure_env="${DRUID_INSECURE_SKIP_TLS_VERIFY:-}"
  if normalize_bool "$insecure_env"; then
    echo "=== TLS: insecureSkipTlsVerify включён через DRUID_INSECURE_SKIP_TLS_VERIFY=$DRUID_INSECURE_SKIP_TLS_VERIFY (ОПАСНО, только dev/test) ==="
  else
    if [[ -z "${DRUID_TRUST_STORE_PATH:-}" ]]; then
      url_for_tls="$(first_https_url || true)"
      host_for_tls=""
      port_for_tls=""
      if [[ -n "$url_for_tls" ]]; then
        read -r host_for_tls port_for_tls <<<"$(parse_host_port_from_https_url "$url_for_tls")"
      fi
      store_path="$ROOT/druid-truststore.p12"
      store_pass="${DRUID_TRUST_STORE_PASSWORD:-changeit}"
      store_type="${DRUID_TRUST_STORE_TYPE:-PKCS12}"

      echo "=== TLS: обнаружены https:// URL. Truststore не задан — генерируем через scripts/create-druid-truststore.sh ==="
      if [[ -n "$host_for_tls" && -n "$port_for_tls" ]]; then
        echo "=== TLS: цель для truststore: ${host_for_tls}:${port_for_tls} (из $url_for_tls) ==="
        "$ROOT/scripts/create-druid-truststore.sh" "$host_for_tls" "$port_for_tls" "$store_path" "$store_pass" >/dev/null
      else
        echo "ОШИБКА: обнаружен https://, но не удалось извлечь host:port из ENV/config.yaml." >&2
        echo "Задайте DRUID_*_URL (https://host:port) или DRUID_TRUST_STORE_PATH/DRUID_TRUST_STORE_PASSWORD вручную." >&2
        exit 2
      fi
      export DRUID_TRUST_STORE_PATH="$store_path"
      export DRUID_TRUST_STORE_PASSWORD="$store_pass"
      export DRUID_TRUST_STORE_TYPE="$store_type"
    fi
    echo "=== TLS truststore: path=${DRUID_TRUST_STORE_PATH:-<not set>} type=${DRUID_TRUST_STORE_TYPE:-<not set>} ==="
  fi
fi

if [[ "$SKIP_GENERATE" == true ]]; then
  echo "=== Пропуск генерации messages (--skip-generate) ==="
else
  echo "=== Генерация $MESSAGE_COUNT сообщений ==="
  set +e
  run_jar generate messages "$MESSAGE_COUNT" 2>&1 | tee "$LOGS_DIR/generate.log"
  gen_rc="${PIPESTATUS[0]}"
  set -e
  if [[ "$gen_rc" -ne 0 ]]; then
    echo "Ошибка генерации (код $gen_rc). См. $LOGS_DIR/generate.log" >&2
    exit "$gen_rc"
  fi
fi

STRATEGIES=(combined compcom eav hybrid default)

echo "=== Парсинг и загрузка в Druid по стратегиям ==="
set +e
for s in "${STRATEGIES[@]}"; do
  variants=('')
  case "$s" in
    combined|compcom)
      if ((${#WARM_VARIANTS[@]} > 0)); then
        variants=("${WARM_VARIANTS[@]}")
      fi
      ;;
  esac

  for warm in "${variants[@]}"; do
    suffix=""
    if [[ -n "${warm:-}" ]]; then
      suffix="_warm${warm}"
    fi
    logf="$LOGS_DIR/${s}_ingest${suffix}.log"
    if [[ -n "${warm:-}" ]]; then
      echo "=== parse + ingest: $s (warm limit $warm) ==="
      export PARSER_WARM_VARIABLES_LIMIT="$warm"
    else
      echo "=== parse + ingest: $s ==="
      unset PARSER_WARM_VARIABLES_LIMIT || true
    fi
    run_jar parse "$s" messages --ingest 2>&1 | tee "$logf"
    rc="${PIPESTATUS[0]}"
    if [[ "$rc" -ne 0 ]]; then
      echo "Предупреждение: ingest для $s${suffix:+ ($warm)} завершился с кодом $rc. Лог: $logf" >&2
    fi
  done
done
unset PARSER_WARM_VARIABLES_LIMIT || true
set -e

echo "=== Выполнение всех SQL по стратегиям → $OUT_DIR ==="
for s in "${STRATEGIES[@]}"; do
  qp="$ROOT/query/$s"
  if [[ ! -d "$qp" ]]; then
    echo "Пропуск запросов для $s: нет каталога $qp"
    continue
  fi

  outf="$OUT_DIR/$s.txt"
  {
    echo "=== Strategy: $s ==="
  } >"$outf"

  sql_count=0
  while IFS= read -r f; do
    [[ -f "$f" ]] || continue
    sql_count=$((sql_count + 1))
    rel="${f#$ROOT/}"
    rel="${rel//\\//}"
    {
      echo ""
      echo "----- $rel -----"
    } >>"$outf"
    set +e
    run_jar query "$rel" >>"$outf" 2>&1
    set -e
  done < <(find "$qp" -type f -name '*.sql' | sort)

  if [[ "$sql_count" -eq 0 ]]; then
    echo "Нет .sql в $qp"
    continue
  fi
  echo "Готово: $outf"
done

echo ""
echo "Конвейер завершён. Логи: $LOGS_DIR | Результаты запросов: $OUT_DIR"
