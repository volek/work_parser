#!/usr/bin/env bash
# =============================================================================
# BPM Parser - Очистка данных от предыдущего запуска
# =============================================================================
# Использование:
#   ./scripts/clean-run-data.sh        — очистка парсера (messages, логи, build)
#   ./scripts/clean-run-data.sh --full — расширенная очистка локальных артефактов
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

# 1. Удалить сгенерированные сообщения
echo "[1/4] Удаление сгенерированных сообщений (messages/)..."
if [[ -d messages ]]; then
  rm -f messages/*
  echo "  Готово"
else
  echo "  Папка messages/ отсутствует — пропуск"
fi

# 2. Локальные логи
echo "[2/4] Удаление локальных логов (logs/)..."
rm -rf logs/* 2>/dev/null || true

# 3. Артефакты сборки
echo "[3/4] Очистка артефактов сборки (build/, .gradle/)..."
rm -rf build .gradle
echo "  Готово"

# 4. Расширенная очистка при --full
if [[ "$FULL_RESET" == true ]]; then
  echo "[4/4] Расширенная очистка: query-results/, druid-truststore.* ..."
  rm -rf query-results/* 2>/dev/null || true
  rm -f druid-truststore.p12 druid-truststore.jks 2>/dev/null || true
  echo "  Готово"
else
  echo "[4/4] Базовая очистка завершена. Для расширенной: $0 --full"
fi

echo ""
echo "Очистка завершена."
