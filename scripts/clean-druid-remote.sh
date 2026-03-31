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

CURL_COMMON_OPTS=(
  --silent
  --show-error
  --connect-timeout "$CONNECT_TIMEOUT_SECONDS"
  --max-time "$READ_TIMEOUT_SECONDS"
)

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

LAST_HTTP_CODE="000"
LAST_CURL_EXIT=0
LAST_DURATION="0.000"
LAST_BODY=""
LAST_ERROR=""

druid_request() {
  local method="$1"
  local url="$2"
  local body_file err_file out curl_ec
  body_file="$(mktemp)"
  err_file="$(mktemp)"
  set +e
  out="$(curl "${CURL_COMMON_OPTS[@]}" -X "$method" -o "$body_file" -w "%{http_code}|%{time_total}" "$url" 2>"$err_file")"
  curl_ec=$?
  set -e

  LAST_BODY="$(<"$body_file")"
  LAST_ERROR="$(<"$err_file")"
  rm -f "$body_file" "$err_file"

  LAST_HTTP_CODE="${out%%|*}"
  LAST_DURATION="${out##*|}"
  LAST_CURL_EXIT="$curl_ec"
  if [[ "$curl_ec" -ne 0 && "$LAST_HTTP_CODE" == "000" ]]; then
    return 1
  fi
  return 0
}

wait_until_gone() {
  local ds="$1"
  local start_ts now elapsed checks
  start_ts="$(date +%s)"
  checks=0

  while true; do
    checks=$((checks + 1))
    druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true
    if [[ "$LAST_HTTP_CODE" == "404" ]]; then
      log "    Confirmed removed: $ds (checks=$checks)"
      return 0
    fi

    now="$(date +%s)"
    elapsed=$((now - start_ts))
    if (( elapsed >= TIMEOUT_SECONDS )); then
      log "    Timeout waiting removal (${TIMEOUT_SECONDS}s): $ds (last_http=$LAST_HTTP_CODE curl_ec=$LAST_CURL_EXIT checks=$checks)"
      return 1
    fi
    sleep "$POLL_SECONDS"
  done
}

count_present_datasources() {
  local present=0 ds
  for ds in "${DATASOURCES[@]}"; do
    druid_request "GET" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true
    if [[ "$LAST_HTTP_CODE" == "200" ]]; then
      present=$((present + 1))
    fi
  done
  echo "$present"
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
present_before="$(count_present_datasources)"
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
log "Polling timeout: ${TIMEOUT_SECONDS}s, interval: ${POLL_SECONDS}s"
log "Curl timeouts: connect=${CONNECT_TIMEOUT_SECONDS}s, read=${READ_TIMEOUT_SECONDS}s"
log "Log file: $LOG_FILE"
log "Initially present datasources from target list: $present_before/$total_ds"
log ""

for ds in "${DATASOURCES[@]}"; do
  seg_before="$(get_segment_count "$ds")"
  log "Datasource: $ds | segments_before=${seg_before}"

  druid_request "DELETE" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true
  mark_code="$LAST_HTTP_CODE"
  if [[ "$mark_code" == "200" ]]; then
    marked_ok=$((marked_ok + 1))
    log "  Marked unused: $ds (http=$mark_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s)"
  elif [[ "$mark_code" == "404" ]]; then
    marked_not_found=$((marked_not_found + 1))
    log "  Skip mark (not found): $ds (http=$mark_code t=${LAST_DURATION}s)"
  else
    mark_errors=$((mark_errors + 1))
    log "  Mark unused error: $ds (http=$mark_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s)"
    [[ -n "$LAST_ERROR" ]] && log "  curl stderr: $LAST_ERROR"
    [[ -n "$LAST_BODY" ]] && log "  response body: $LAST_BODY"
    continue
  fi

  druid_request "DELETE" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds?kill=true&interval=$KILL_INTERVAL" || true
  kill_code="$LAST_HTTP_CODE"
  if [[ "$kill_code" == "200" ]]; then
    kill_ok=$((kill_ok + 1))
    log "    Kill requested: $ds (http=$kill_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s)"
  elif [[ "$kill_code" == "404" ]]; then
    kill_not_found=$((kill_not_found + 1))
    log "    Skip kill (not found): $ds (http=$kill_code t=${LAST_DURATION}s)"
  else
    kill_errors=$((kill_errors + 1))
    log "    Kill error: $ds (http=$kill_code curl_ec=$LAST_CURL_EXIT t=${LAST_DURATION}s)"
    [[ -n "$LAST_ERROR" ]] && log "    curl stderr: $LAST_ERROR"
    [[ -n "$LAST_BODY" ]] && log "    response body: $LAST_BODY"
    continue
  fi

  if ! wait_until_gone "$ds"; then
    remove_timeouts=$((remove_timeouts + 1))
    log "    WARNING: datasource still present after timeout: $ds"
  else
    confirmed_removed=$((confirmed_removed + 1))
  fi
done

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
