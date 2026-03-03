# =============================================================================
# BPM Message Parser - Multi-stage Dockerfile
# =============================================================================
# Build stage: компиляция приложения с использованием Gradle
# Runtime stage: минимальный образ для запуска JAR
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM gradle:8.5-jdk17-alpine AS builder

WORKDIR /app

# Копируем файлы конфигурации Gradle для кэширования зависимостей
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle.properties* ./

# Загружаем зависимости (кэшируется при неизменных build-файлах)
RUN gradle dependencies --no-daemon || true

# Копируем исходный код
COPY src ./src

# Собираем fat JAR
RUN gradle jar --no-daemon

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Sber BPM Team"
LABEL description="BPM Message Parser for Apache Druid"
LABEL version="1.0.0"

# Создаём пользователя без привилегий
RUN addgroup -S parser && adduser -S parser -G parser

WORKDIR /app

# Копируем JAR из builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Копируем ресурсы (samples, query, messages)
COPY --chown=parser:parser samples ./samples
COPY --chown=parser:parser query ./query
COPY --chown=parser:parser messages ./messages

# Создаём директорию для логов
RUN mkdir -p /app/logs && chown -R parser:parser /app

# Переключаемся на непривилегированного пользователя
USER parser

# Переменные окружения для конфигурации
ENV DRUID_BROKER_URL=http://druid-router:8888 \
    DRUID_COORDINATOR_URL=http://druid-coordinator:8081 \
    DRUID_OVERLORD_URL=http://druid-overlord:8090 \
    DRUID_ROUTER_URL=http://druid-router:8888 \
    DRUID_CONNECT_TIMEOUT=30000 \
    DRUID_READ_TIMEOUT=60000 \
    DRUID_BATCH_SIZE=1000 \
    JAVA_OPTS="-Xms256m -Xmx512m"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD java -version || exit 1

# Точка входа
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar $@", "--"]

# Команда по умолчанию
CMD ["help"]
