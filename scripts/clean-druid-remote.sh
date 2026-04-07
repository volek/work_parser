#!/usr/bin/env bash
# =============================================================================
# Удаление datasource'ов парсера в Druid на отдельном хосте
# =============================================================================
# Использование:
#   COORDINATOR_URL=http://druid-host:8081 ./scripts/clean-druid-remote.sh
#   ./scripts/clean-druid-remote.sh http://druid-host:8081
#
# Удаляются только datasource'ы, создаваемые BPM Parser:
#   Hybrid:   hybrid_process_hybrid
#   EAV:      eav_process_events, eav_process_variables
#   Combined: combined_process_main, combined_process_variables_indexed
#   Compcom:  compcom_process_main_compact, compcom_process_variables_indexed
#   Default:  default_process_default
#
# Требуется URL Coordinator (порт 8081), не Router (8888).
#
# Ожидание удаления: батчевый опрос списка datasource'ов (быстрее, чем ждать
# каждый по очереди). Лимит времени: DRUID_CLEANUP_BATCH_TIMEOUT_SECONDS или
# TIMEOUT_SECONDS * число ожидаемых ds.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_FILE="${PARSER_CONFIG_PATH:-$ROOT/config.yaml}"
LOGS_DIR="$ROOT/logs"
mkdir -p "$LOGS_DIR"
LOG_FILE="${DRUID_CLEANUP_LOG_FILE:-$LOGS_DIR/clean_druid_remote.log}"
exec > >(tee -a "$LOG_FILE") 2>&1

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
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
    # Legacy/single-value key
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
    # Current config uses list key: coordinatorUrls:
    in_druid && /^[[:space:]]*coordinatorUrls:[[:space:]]*$/ { in_coord_list=1; next }
    in_coord_list && /^[[:space:]]*-[[:space:]]*/ {
      line=$0
      sub(/^[[:space:]]*-[[:space:]]*/, "", line)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line ~ /^".*"$/ || line ~ /^'\''.*'\''$/) {
        line=substr(line, 2, length(line)-2)
      }
      print line
      exit
    }
    # Any new top-level key or sibling key under druid ends list parsing.
    in_coord_list && /^[^[:space:]]/ { in_coord_list=0 }
    in_coord_list && /^[[:space:]]*[a-zA-Z0-9_]+:[[:space:]]*/ { in_coord_list=0 }
  ' "$cfg"
}

read_config_druid_auth() {
  local cfg="$1"
  [[ -f "$cfg" ]] || return 0

  awk '
    BEGIN {
      in_druid=0
      user=""
      pass=""
    }
    /^[[:space:]]*druid:[[:space:]]*$/ { in_druid=1; next }
    in_druid && /^[^[:space:]]/ { in_druid=0 }
    in_druid && /^[[:space:]]*username:[[:space:]]*/ {
      line=$0
      sub(/^[[:space:]]*username:[[:space:]]*/, "", line)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line ~ /^".*"$/ || line ~ /^'\''.*'\''$/) {
        line=substr(line, 2, length(line)-2)
      }
      user=line
      next
    }
    in_druid && /^[[:space:]]*password:[[:space:]]*/ {
      line=$0
      sub(/^[[:space:]]*password:[[:space:]]*/, "", line)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line ~ /^".*"$/ || line ~ /^'\''.*'\''$/) {
        line=substr(line, 2, length(line)-2)
      }
      pass=line
      next
    }
    END {
      print user "\t" pass
    }
  ' "$cfg"
}

CONFIG_COORD="$(read_config_coordinator_url "$CONFIG_FILE")"
COORDINATOR_URL="${COORDINATOR_URL:-${DRUID_COORDINATOR_URL:-${1:-${CONFIG_COORD:-}}}}"
if [[ -z "$COORDINATOR_URL" ]]; then
  echo "Usage: COORDINATOR_URL=http://host:8081 $0"
  echo "   or: DRUID_COORDINATOR_URL=http://host:8081 $0"
  echo "   or: $0 http://host:8081"
  echo ""
  echo "Coordinator URL was not found in ENV or config file: $CONFIG_FILE"
  exit 1
fi

# Убираем trailing slash
COORDINATOR_URL="${COORDINATOR_URL%/}"

CONFIG_AUTH_RAW="$(read_config_druid_auth "$CONFIG_FILE")"
CONFIG_USERNAME="${CONFIG_AUTH_RAW%%$'\t'*}"
if [[ "$CONFIG_AUTH_RAW" == *$'\t'* ]]; then
  CONFIG_PASSWORD="${CONFIG_AUTH_RAW#*$'\t'}"
else
  CONFIG_PASSWORD=""
fi
DRUID_USERNAME="${DRUID_USERNAME:-${CONFIG_USERNAME:-}}"
DRUID_PASSWORD="${DRUID_PASSWORD:-${CONFIG_PASSWORD:-}}"

DATASOURCES=(
  hybrid_process_hybrid
  eav_process_events
  eav_process_variables
  combined_process_main
  combined_process_variables_indexed
  compcom_process_main_compact
  compcom_process_variables_indexed
  default_process_default
)

POLL_SECONDS="${DRUID_CLEANUP_POLL_SECONDS:-3}"
TIMEOUT_SECONDS="${DRUID_CLEANUP_TIMEOUT_SECONDS:-180}"
KILL_INTERVAL="1000-01-01T00:00:00.000Z/3000-01-01T00:00:00.000Z"
CONNECT_TIMEOUT_SECONDS="${DRUID_CONNECT_TIMEOUT:-10}"
READ_TIMEOUT_SECONDS="${DRUID_READ_TIMEOUT:-60}"
CA_CERT_PATH="${DRUID_CA_CERT_PATH:-}"
INSECURE_TLS_RAW="${DRUID_INSECURE_SKIP_TLS_VERIFY:-auto}"
START_TS="$(date +%s)"

# Переиспользуемые файлы вместо mktemp на каждый запрос (заметно ускоряет).
CLEANUP_BODY_FILE="${TMPDIR:-/tmp}/druid_clean_body.$$"
CLEANUP_ERR_FILE="${TMPDIR:-/tmp}/druid_clean_err.$$"
cleanup_temp_files() {
  rm -f "$CLEANUP_BODY_FILE" "$CLEANUP_ERR_FILE"
}
trap cleanup_temp_files EXIT

CURL_COMMON_OPTS=(
  --silent
  --show-error
  --location
  --max-redirs "${DRUID_CLEANUP_MAX_REDIRECTS:-10}"
  --connect-timeout "$CONNECT_TIMEOUT_SECONDS"
  --max-time "$READ_TIMEOUT_SECONDS"
)

if normalize_bool "${DRUID_CLEANUP_LOCATION_TRUSTED:-false}"; then
  # Allow forwarding credentials on redirects to another host when explicitly enabled.
  CURL_COMMON_OPTS+=(--location-trusted)
fi

if [[ -n "$CA_CERT_PATH" ]]; then
  if [[ -f "$CA_CERT_PATH" ]]; then
    CURL_COMMON_OPTS+=(--cacert "$CA_CERT_PATH")
  else
    log "WARNING: DRUID_CA_CERT_PATH указан, но файл не найден: $CA_CERT_PATH"
  fi
fi

if normalize_bool "$INSECURE_TLS_RAW"; then
  CURL_COMMON_OPTS+=(-k)
elif [[ "$INSECURE_TLS_RAW" == "auto" ]] && [[ "$COORDINATOR_URL" == https://* ]] && [[ -z "$CA_CERT_PATH" ]]; then
  # Для curl truststore JVM не используется, поэтому для https без CA включаем fallback.
  CURL_COMMON_OPTS+=(-k)
  log "WARNING: HTTPS без DRUID_CA_CERT_PATH: включен insecure TLS fallback (-k)."
fi

if [[ -n "$DRUID_USERNAME" ]]; then
  CURL_COMMON_OPTS+=(-u "${DRUID_USERNAME}:${DRUID_PASSWORD}")
fi

LAST_HTTP_CODE="000"
LAST_CURL_EXIT=0
LAST_DURATION="0.000"
LAST_REDIRECTS="0"
LAST_EFFECTIVE_URL=""
LAST_BODY=""
LAST_ERROR=""

druid_request() {
  local method="$1"
  local url="$2"
  local out curl_ec
  : >"$CLEANUP_ERR_FILE"
  set +e
  out="$(curl "${CURL_COMMON_OPTS[@]}" -X "$method" -o "$CLEANUP_BODY_FILE" -w "%{http_code}|%{time_total}|%{num_redirects}|%{url_effective}" "$url" 2>"$CLEANUP_ERR_FILE")"
  curl_ec=$?
  set -e

  LAST_BODY="$(<"$CLEANUP_BODY_FILE")"
  LAST_ERROR="$(<"$CLEANUP_ERR_FILE")"

  LAST_HTTP_CODE="${out%%|*}"
  local rest
  rest="${out#*|}"
  LAST_DURATION="${rest%%|*}"
  rest="${rest#*|}"
  LAST_REDIRECTS="${rest%%|*}"
  LAST_EFFECTIVE_URL="${rest#*|}"
  LAST_CURL_EXIT="$curl_ec"
  if [[ "$curl_ec" -ne 0 && "$LAST_HTTP_CODE" == "000" ]]; then
    return 1
  fi
  return 0
}

# Сколько имён из DATASOURCES присутствует в JSON-массиве LAST_BODY (ответ GET /datasources).
count_targets_in_list_body() {
  local list_json="$1"
  shift
  DRUID_LIST_JSON="$list_json" python3 - "$@" <<'PY'
import json, os, sys
# argv: [python, -, ds1, ds2, ...]
want = set(sys.argv[2:])
data = json.loads(os.environ["DRUID_LIST_JSON"])
names = data if isinstance(data, list) else []
print(sum(1 for n in names if n in want))
PY
}

# Оставшиеся из pending, которые ещё есть в JSON списка coordinator.
filter_still_present() {
  local list_json="$1"
  shift
  printf '%s\n' "$@" | DRUID_LIST_JSON="$list_json" python3 -c '
import json, os, sys
pending = [l.strip() for l in sys.stdin if l.strip()]
data = json.loads(os.environ["DRUID_LIST_JSON"])
have = set(data if isinstance(data, list) else [])
for p in pending:
    if p in have:
        print(p)
'
}

# При неуспешном завершении wait_batch_until_gone: какие ds ещё в кластере.
LAST_BATCH_STILL_PENDING=()

wait_batch_until_gone() {
  local -a pending=("$@")
  local n start_ts checks elapsed batch_timeout now still
  LAST_BATCH_STILL_PENDING=()
  n=${#pending[@]}
  (( n == 0 )) && return 0

  if [[ -n "${DRUID_CLEANUP_BATCH_TIMEOUT_SECONDS:-}" ]]; then
    batch_timeout="$DRUID_CLEANUP_BATCH_TIMEOUT_SECONDS"
  else
    batch_timeout=$((TIMEOUT_SECONDS * n))
  fi
  (( batch_timeout < TIMEOUT_SECONDS )) && batch_timeout=$TIMEOUT_SECONDS

  start_ts="$(date +%s)"
  checks=0
  log "  Ожидание исчезновения ${n} datasource(s), таймаут батча ${batch_timeout}s, шаг ${POLL_SECONDS}s..."

  while ((${#pending[@]} > 0)); do
    checks=$((checks + 1))
    druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources" || true
    if [[ "$LAST_HTTP_CODE" != "200" ]]; then
      now="$(date +%s)"
      elapsed=$((now - start_ts))
      if (( elapsed >= batch_timeout )); then
        log "    Таймаут: не удалось получить список datasource'ов за ${batch_timeout}s (last_http=$LAST_HTTP_CODE)"
        LAST_BATCH_STILL_PENDING=("${pending[@]}")
        return 1
      fi
      log "    Предупреждение: список datasource'ов недоступен (http=$LAST_HTTP_CODE), повтор через ${POLL_SECONDS}s..."
      sleep "$POLL_SECONDS"
      continue
    fi

    mapfile -t still < <(filter_still_present "$LAST_BODY" "${pending[@]}")
    if ((${#still[@]} == 0)); then
      log "    Подтверждено удаление всех ${n} datasource(s) (опросов=$checks)"
      return 0
    fi
    pending=("${still[@]}")

    now="$(date +%s)"
    elapsed=$((now - start_ts))
    if (( elapsed >= batch_timeout )); then
      log "    Таймаут ожидания (${batch_timeout}s), ещё присутствуют: ${pending[*]} (опросов=$checks)"
      LAST_BATCH_STILL_PENDING=("${pending[@]}")
      return 1
    fi
    sleep "$POLL_SECONDS"
  done
  return 0
}

count_present_datasources() {
  druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources" || true
  if [[ "$LAST_HTTP_CODE" != "200" ]]; then
    echo "0"
    return 0
  fi
  count_targets_in_list_body "$LAST_BODY" "${DATASOURCES[@]}"
}

get_segment_count() {
  local ds="$1"
  druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds/segments" || true
  if [[ "$LAST_HTTP_CODE" != "200" ]]; then
    echo "-1"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$LAST_BODY" | python3 -c 'import json,sys; s=sys.stdin.read().strip(); print(len(json.loads(s)) if s else 0)' 2>/dev/null || echo "-1"
  else
    echo "-1"
  fi
}

total_ds="${#DATASOURCES[@]}"
present_before=0
marked_ok=0
marked_not_found=0
mark_errors=0
kill_ok=0
kill_not_found=0
kill_errors=0
confirmed_removed=0
remove_timeouts=0

log "Druid Coordinator: $COORDINATOR_URL"
log "Datasources to delete ($total_ds): ${DATASOURCES[*]}"
log "Таймаут на один ds (эталон): ${TIMEOUT_SECONDS}s; батч: DRUID_CLEANUP_BATCH_TIMEOUT_SECONDS или ${TIMEOUT_SECONDS}×N ожидаемых ds"
log "Curl timeouts: connect=${CONNECT_TIMEOUT_SECONDS}s, read=${READ_TIMEOUT_SECONDS}s"
if [[ -n "$DRUID_USERNAME" ]]; then
  log "Auth: basic"
else
  log "Auth: none"
fi
log "Log file: $LOG_FILE"

druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources" || true
if [[ "$LAST_HTTP_CODE" == "401" || "$LAST_HTTP_CODE" == "403" ]]; then
  log "ERROR: Authorization failed for coordinator API (http=$LAST_HTTP_CODE)."
  log "Set DRUID_USERNAME/DRUID_PASSWORD or druid.username/password in config.yaml."
  [[ -n "$LAST_BODY" ]] && log "response body: $LAST_BODY"
  exit 1
fi

present_before="$(count_present_datasources)"
log "Initially present datasources from target list: $present_before/$total_ds"
log ""

declare -a PENDING_WAIT=()

for ds in "${DATASOURCES[@]}"; do
  seg_before="$(get_segment_count "$ds")"
  log "Datasource: $ds | segments_before=${seg_before}"

  druid_request "DELETE" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true
  mark_code="$LAST_HTTP_CODE"
  if [[ "$mark_code" == "200" ]]; then
    marked_ok=$((marked_ok + 1))
    log "  Marked unused: $ds (http=$mark_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
  elif [[ "$mark_code" == "404" ]]; then
    marked_not_found=$((marked_not_found + 1))
    log "  Skip mark (not found): $ds (http=$mark_code t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
  else
    mark_errors=$((mark_errors + 1))
    log "  Mark unused error: $ds (http=$mark_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
    [[ -n "$LAST_EFFECTIVE_URL" ]] && log "  effective_url: $LAST_EFFECTIVE_URL"
    [[ -n "$LAST_ERROR" ]] && log "  curl stderr: $LAST_ERROR"
    [[ -n "$LAST_BODY" ]] && log "  response body: $LAST_BODY"
    continue
  fi

  druid_request "DELETE" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds?kill=true&interval=$KILL_INTERVAL" || true
  kill_code="$LAST_HTTP_CODE"
  if [[ "$kill_code" == "200" ]]; then
    kill_ok=$((kill_ok + 1))
    log "    Kill requested: $ds (http=$kill_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
  elif [[ "$kill_code" == "404" ]]; then
    kill_not_found=$((kill_not_found + 1))
    log "    Skip kill (not found): $ds (http=$kill_code t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
  else
    kill_errors=$((kill_errors + 1))
    log "    Kill error: $ds (http=$kill_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s redirects=$LAST_REDIRECTS)"
    [[ -n "$LAST_EFFECTIVE_URL" ]] && log "    effective_url: $LAST_EFFECTIVE_URL"
    [[ -n "$LAST_ERROR" ]] && log "    curl stderr: $LAST_ERROR"
    [[ -n "$LAST_BODY" ]] && log "    response body: $LAST_BODY"
    continue
  fi

  PENDING_WAIT+=("$ds")
done

if ((${#PENDING_WAIT[@]} > 0)); then
  n_wait=${#PENDING_WAIT[@]}
  if ! wait_batch_until_gone "${PENDING_WAIT[@]}"; then
    remove_timeouts=${#LAST_BATCH_STILL_PENDING[@]}
    confirmed_removed=$((n_wait - remove_timeouts))
    log "  WARNING: таймаут батча; ещё в кластере: ${LAST_BATCH_STILL_PENDING[*]:-нет}"
  else
    confirmed_removed=$n_wait
  fi
else
  log "Нет datasource'ов для ожидания удаления (kill не выполнялся успешно ни для одного)."
fi

present_after="$(count_present_datasources)"
end_ts="$(date +%s)"
elapsed_total=$((end_ts - START_TS))

log ""
log "Remaining datasources after cleanup (full list from coordinator):"
druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources" || true
if [[ "$LAST_HTTP_CODE" == "200" ]]; then
  printf '%s\n' "$LAST_BODY"
else
  log "Cannot load datasource list (http=$LAST_HTTP_CODE curl_ec=$LAST_CURL_EXIT)"
  [[ -n "$LAST_ERROR" ]] && log "curl stderr: $LAST_ERROR"
fi

log ""
log "Cleanup metrics:"
log "  datasource_total=$total_ds"
log "  datasource_present_before=$present_before"
log "  datasource_present_after=$present_after"
log "  mark_ok=$marked_ok mark_not_found=$marked_not_found mark_errors=$mark_errors"
log "  kill_ok=$kill_ok kill_not_found=$kill_not_found kill_errors=$kill_errors"
log "  confirmed_removed=$confirmed_removed remove_timeouts=$remove_timeouts"
log "  elapsed_seconds=$elapsed_total"
log "Done."
