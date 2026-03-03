#!/bin/bash
# =============================================================================
# Druid Entrypoint Script
# =============================================================================
# Скрипт инициализации Druid с проверкой зависимостей
# =============================================================================

set -e

echo "=================================================="
echo "  Apache Druid - Starting..."
echo "=================================================="

# Функция для проверки доступности сервиса
wait_for_service() {
    local host=$1
    local port=$2
    local service_name=$3
    local max_attempts=${4:-30}
    local attempt=1
    
    echo "Waiting for $service_name ($host:$port)..."
    
    while [ $attempt -le $max_attempts ]; do
        if nc -z "$host" "$port" 2>/dev/null; then
            echo "✓ $service_name is available"
            return 0
        fi
        echo "  Attempt $attempt/$max_attempts - $service_name not ready..."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo "✗ Failed to connect to $service_name after $max_attempts attempts"
    return 1
}

# Проверка переменных окружения
if [ -n "$DRUID_ZK_HOST" ]; then
    ZK_HOST="${DRUID_ZK_HOST%%:*}"
    ZK_PORT="${DRUID_ZK_HOST##*:}"
    wait_for_service "$ZK_HOST" "${ZK_PORT:-2181}" "ZooKeeper"
fi

if [ -n "$DRUID_METADATA_HOST" ]; then
    META_HOST="${DRUID_METADATA_HOST%%:*}"
    META_PORT="${DRUID_METADATA_HOST##*:}"
    wait_for_service "$META_HOST" "${META_PORT:-5432}" "PostgreSQL"
fi

# Настройка JVM
export DRUID_XMS=${DRUID_XMS:-1g}
export DRUID_XMX=${DRUID_XMX:-2g}
export DRUID_MAXDIRECTMEMORYSIZE=${DRUID_MAXDIRECTMEMORYSIZE:-2g}

echo ""
echo "JVM Settings:"
echo "  -Xms: $DRUID_XMS"
echo "  -Xmx: $DRUID_XMX"
echo "  -XX:MaxDirectMemorySize: $DRUID_MAXDIRECTMEMORYSIZE"
echo ""

# Создание директорий если не существуют
mkdir -p /opt/druid/var/tmp
mkdir -p /opt/druid/var/druid/segment-cache
mkdir -p /opt/druid/var/druid/indexing-logs
mkdir -p /opt/druid/var/druid/task
mkdir -p /opt/druid/var/druid/segments

echo "Starting Druid with command: $@"
echo "=================================================="

# Запуск Druid
exec /druid.sh "$@"
