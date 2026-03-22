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

usage() {
  cat <<EOF
Использование: $0 [опции]

  -m, --message-count N   Число генерируемых сообщений (по умолчанию 500)
  -w, --warm-variants L   Список лимитов warm через запятую для combined/compcom (напр. 10,110,210)
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
  "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" "$@"
}

LOGS_DIR="$ROOT/logs"
OUT_DIR="$ROOT/query-results"
MESSAGES_DIR="$ROOT/messages"

echo "=== Очистка артефактов: logs, query-results, messages ==="
mkdir -p "$LOGS_DIR" "$OUT_DIR" "$MESSAGES_DIR"
rm -rf "${LOGS_DIR:?}/"* 2>/dev/null || true
rm -rf "${OUT_DIR:?}/"* 2>/dev/null || true
rm -rf "${MESSAGES_DIR:?}/"* 2>/dev/null || true

COORD="${COORDINATOR_URL:-${DRUID_COORDINATOR_URL:-}}"
if [[ -n "$COORD" ]]; then
  echo "=== Очистка datasource'ов в Druid (Coordinator: $COORD) ==="
  COORDINATOR_URL="$COORD" "$ROOT/scripts/clean-druid-remote.sh" || {
    echo "Предупреждение: clean-druid-remote.sh завершился с ошибкой, продолжаем." >&2
  }
else
  echo "Пропуск очистки Druid: не заданы COORDINATOR_URL / DRUID_COORDINATOR_URL."
fi

echo "=== Генерация $MESSAGE_COUNT сообщений ==="
set +e
run_jar generate messages "$MESSAGE_COUNT" 2>&1 | tee "$LOGS_DIR/generate.log"
gen_rc="${PIPESTATUS[0]}"
set -e
if [[ "$gen_rc" -ne 0 ]]; then
  echo "Ошибка генерации (код $gen_rc). См. $LOGS_DIR/generate.log" >&2
  exit "$gen_rc"
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
