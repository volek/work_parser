#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RUN_SCRIPT="$ROOT/scripts/run-all-strategies.sh"
LOG_DIR="$ROOT/logs"
PID_FILE="$LOG_DIR/run-all.pid"
NOHUP_LOG="$LOG_DIR/run-all.nohup.log"

usage() {
  cat <<EOF
Usage:
  $0 start [args for run-all-strategies.sh]
  $0 status
  $0 logs
  $0 stop

Examples:
  $0 start -m 100
  $0 start -m 200 -w 10,110,210 --skip-generate
  $0 status
  $0 logs
  $0 stop
EOF
}

is_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

cmd="${1:-}"
case "$cmd" in
  start)
    shift || true
    mkdir -p "$LOG_DIR"
    if is_running; then
      echo "run-all уже запущен (pid=$(cat "$PID_FILE"))."
      exit 0
    fi
    nohup "$RUN_SCRIPT" "$@" >"$NOHUP_LOG" 2>&1 < /dev/null &
    echo $! >"$PID_FILE"
    echo "Запущено в фоне: pid=$(cat "$PID_FILE")"
    echo "Лог: $NOHUP_LOG"
    ;;
  status)
    if is_running; then
      pid="$(cat "$PID_FILE")"
      echo "RUNNING pid=$pid"
      ps -fp "$pid" || true
    else
      echo "NOT RUNNING"
      exit 1
    fi
    ;;
  logs)
    mkdir -p "$LOG_DIR"
    touch "$NOHUP_LOG"
    tail -f "$NOHUP_LOG"
    ;;
  stop)
    if is_running; then
      pid="$(cat "$PID_FILE")"
      kill "$pid"
      echo "Остановлено: pid=$pid"
      rm -f "$PID_FILE"
    else
      echo "Процесс не запущен."
      rm -f "$PID_FILE"
    fi
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "Неизвестная команда: $cmd" >&2
    usage
    exit 1
    ;;
esac
