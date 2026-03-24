#!/usr/bin/env bash
# =============================================================================
# BPM Parser - Очистка данных от предыдущего запуска
# =============================================================================
# Использование:
#   ./scripts/clean-run-data.sh        — очистка парсера (messages, логи, build)
#   ./scripts/clean-run-data.sh --full — полный сброс + Druid тома
# =============================================================================

set -e
FULL_RESET=false
[[ "${1:-}" == "--full" ]] && FULL_RESET=true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "BPM Parser: очистка данных предыдущего запуска"
echo "Корень проекта: $PROJECT_ROOT"
echo ""

# 1. Остановить контейнеры парсера и удалить его тома (parser-logs)
echo "[1/5] Остановка контейнеров парсера и удаление томов..."
docker compose down -v 2>/dev/null || true

# 2. Удалить сгенерированные сообщения
echo "[2/5] Удаление сгенерированных сообщений (messages/)..."
if [[ -d messages ]]; then
  rm -f messages/*
  echo "  Готово"
else
  echo "  Папка messages/ отсутствует — пропуск"
fi

# 3. Локальные логи (том уже удалён в шаге 1 через down -v)
echo "[3/5] Удаление локальных логов (logs/)..."
rm -rf logs/* 2>/dev/null || true

# 4. Артефакты сборки
echo "[4/5] Очистка артефактов сборки (build/, .gradle/)..."
rm -rf build .gradle
echo "  Готово"

# 5. Полный сброс при --full
if [[ "$FULL_RESET" == true ]]; then
  echo "[5/5] Полный сброс: Druid + все тома..."
  docker compose -f docker-compose.druid.yml down -v 2>/dev/null || true
  docker compose down -v 2>/dev/null || true
  echo "  Контейнеры и тома удалены"
else
  echo "[5/5] Без полного сброса. Для сброса Druid: $0 --full"
fi

echo ""
echo "Очистка завершена."
