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

set -e

COORDINATOR_URL="${COORDINATOR_URL:-${1:-}}"
if [[ -z "$COORDINATOR_URL" ]]; then
  echo "Usage: COORDINATOR_URL=http://host:8081 $0"
  echo "   or: $0 http://host:8081"
  echo ""
  echo "COORDINATOR_URL must point to Druid Coordinator (default port 8081)."
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

echo "Druid Coordinator: $COORDINATOR_URL"
echo "Datasources to delete: ${DATASOURCES[*]}"
echo ""

for ds in "${DATASOURCES[@]}"; do
  if response=$(curl -s -w "\n%{http_code}" -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds"); then
    code=$(echo "$response" | tail -n1)
    if [[ "$code" == "200" ]]; then
      echo "  Deleted: $ds"
    elif [[ "$code" == "404" ]]; then
      echo "  Skip (not found): $ds"
    else
      echo "  Error $code: $ds"
      echo "$response" | head -n -1
    fi

    # Дополнительно удаляем все сегменты datasource (kill task).
    if [[ "$ds" == "compcom_process_main_compact" ]]; then
      if seg_response=$(curl -s -w "\n%{http_code}" -X DELETE "$COORDINATOR_URL/druid/coordinator/v1/datasources/$ds?kill=true&interval=1000-01-01T00:00:00.000Z/3000-01-01T00:00:00.000Z"); then
        seg_code=$(echo "$seg_response" | tail -n1)
        if [[ "$seg_code" == "200" ]]; then
          echo "    Segments kill requested: $ds"
        elif [[ "$seg_code" == "404" ]]; then
          echo "    Segments skip (not found): $ds"
        else
          echo "    Segments error $seg_code: $ds"
          echo "$seg_response" | head -n -1
        fi
      else
        echo "    Failed to reach Coordinator for segments: $ds"
      fi
    fi
  else
    echo "  Failed to reach Coordinator: $ds"
  fi
done

echo ""
echo "Done. List datasources: curl -s $COORDINATOR_URL/druid/coordinator/v1/datasources"
