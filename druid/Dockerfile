# =============================================================================
# Apache Druid - Custom Single-Server Image
# =============================================================================
# Расширенный образ Druid с предустановленными конфигурациями
# для BPM Parser проекта.
#
# Build:
#   docker build -t bpm-druid:latest -f druid/Dockerfile .
#
# Run:
#   docker run -d -p 8888:8888 -p 8082:8082 --name druid bpm-druid:latest
# =============================================================================

FROM apache/druid:28.0.0

LABEL maintainer="Sber BPM Team"
LABEL description="Apache Druid single-server for BPM Message Parser"
LABEL version="1.0"

# Установка дополнительных утилит
USER root

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    jq \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Копирование конфигурационных файлов
COPY druid/conf/ /opt/druid/conf/druid/single-server/micro-quickstart/

# Копирование скриптов инициализации
COPY druid/scripts/ /opt/druid/scripts/
RUN chmod +x /opt/druid/scripts/*.sh

# Возврат к пользователю druid
USER druid

# Environment variables
ENV DRUID_SINGLE_NODE=true \
    DRUID_XMS=1g \
    DRUID_XMX=2g \
    DRUID_MAXDIRECTMEMORYSIZE=2g

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD wget -q --spider http://localhost:8888/status/health || exit 1

# Expose ports
EXPOSE 8081 8082 8083 8088 8090 8091 8100-8105 8888

# Entry point
ENTRYPOINT ["/opt/druid/scripts/entrypoint.sh"]
CMD ["start-druid"]
