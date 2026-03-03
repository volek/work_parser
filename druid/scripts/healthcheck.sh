#!/bin/bash
# =============================================================================
# Druid Health Check Script
# =============================================================================
# Проверка состояния всех компонентов Druid
# =============================================================================

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_endpoint() {
    local name=$1
    local url=$2
    
    if curl -s --fail "$url" > /dev/null 2>&1; then
        echo -e "${GREEN}✓${NC} $name: OK"
        return 0
    else
        echo -e "${RED}✗${NC} $name: FAILED"
        return 1
    fi
}

echo "========================================"
echo "  Druid Health Check"
echo "========================================"

# Проверка основных endpoints
check_endpoint "Router (Console)" "http://localhost:8888/status/health"
check_endpoint "Coordinator" "http://localhost:8081/status/health"
check_endpoint "Broker" "http://localhost:8082/status/health"
check_endpoint "Overlord" "http://localhost:8090/status/health"
check_endpoint "Historical" "http://localhost:8083/status/health"
check_endpoint "MiddleManager" "http://localhost:8091/status/health"

echo "========================================"

# Проверка SQL
echo ""
echo "Testing SQL endpoint..."
SQL_RESULT=$(curl -s -X POST "http://localhost:8888/druid/v2/sql" \
    -H "Content-Type: application/json" \
    -d '{"query": "SELECT 1 as test"}' 2>/dev/null)

if echo "$SQL_RESULT" | grep -q "test"; then
    echo -e "${GREEN}✓${NC} SQL Query: OK"
else
    echo -e "${RED}✗${NC} SQL Query: FAILED"
fi

# Получение списка datasources
echo ""
echo "Available Datasources:"
curl -s "http://localhost:8888/druid/v2/datasources" 2>/dev/null | jq -r '.[]' 2>/dev/null || echo "  (none)"

echo ""
echo "========================================"
echo "  Health Check Complete"
echo "========================================"
