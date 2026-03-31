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

wait_until_gone() {
  local ds="$1"
  local start_ts now elapsed
  start_ts="$(date +%s)"

  while true; do
    local status
    status="$(curl -s -o /dev/null -w "%{http_code}" "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true)"
    if [[ "$status" == "404" ]]; then
      echo "    Confirmed removed: $ds"
      return 0
    fi

    now="$(date +%s)"
    elapsed=$((now - start_ts))
    if (( elapsed >= TIMEOUT_SECONDS )); then
      echo "    Timeout waiting removal ($TIMEOUT_SECONDS s): $ds (last status: $status)"
      return 1
    fi
    sleep "$POLL_SECONDS"
  done
}

echo "Druid Coordinator: $COORDINATOR_URL"
echo "Datasources to delete: ${DATASOURCES[*]}"
echo "Polling timeout: ${TIMEOUT_SECONDS}s, interval: ${POLL_SECONDS}s"
echo ""

for ds in "${DATASOURCES[@]}"; do
  mark_resp="$(curl -s -w "\n%{http_code}" -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds" || true)"
  mark_code="$(echo "$mark_resp" | tail -n1)"
  if [[ "$mark_code" == "200" ]]; then
    echo "  Marked unused: $ds"
  elif [[ "$mark_code" == "404" ]]; then
    echo "  Skip mark (not found): $ds"
  else
    echo "  Mark unused error $mark_code: $ds"
    echo "$mark_resp" | head -n -1
    continue
  fi

  kill_resp="$(curl -s -w "\n%{http_code}" -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds?kill=true&interval=$KILL_INTERVAL" || true)"
  kill_code="$(echo "$kill_resp" | tail -n1)"
  if [[ "$kill_code" == "200" ]]; then
    echo "    Kill requested: $ds"
  elif [[ "$kill_code" == "404" ]]; then
    echo "    Skip kill (not found): $ds"
  else
    echo "    Kill error $kill_code: $ds"
    echo "$kill_resp" | head -n -1
    continue
  fi

  if ! wait_until_gone "$ds"; then
    echo "    WARNING: datasource still present after timeout: $ds"
  fi
done

echo ""
echo "Remaining datasources after cleanup:"
curl -s "$COORDINATOR_URL/druid/coordinator/v1/datasources" || true
echo ""
echo "Done."
