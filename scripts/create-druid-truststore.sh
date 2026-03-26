#!/usr/bin/env bash
set -euo pipefail

# Создаёт PKCS12 truststore для TLS-подключений к Druid.
#
# Режим сборки truststore (по умолчанию: local+chain):
#  - local: только импорт локальных сертификатов из `distribution/cert` (без сетевого запроса)
#  - chain: только импорт сертификатов из полной TLS-цепочки целевого host:port
#  - local+chain: сначала импорт локальных, затем добавляем цепочку с host:port
#  - auto: если локальные сертификаты есть — только local, иначе — chain
#
# Использование:
#   ./scripts/create-druid-truststore.sh
#   ./scripts/create-druid-truststore.sh <host> <port> <output_store> <store_pass>
#
# Значения по умолчанию:
#   host         omltd-abyss-sdp2-druid-10.opsmon.sbt
#   port         8290
#   output_store druid-truststore.p12
#   store_pass   changeit

HOST="${1:-omltd-abyss-sdp2-druid-10.opsmon.sbt}"
PORT="${2:-8290}"
STORE="${3:-druid-truststore.p12}"
PASS="${4:-changeit}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CERT_DIR="${DRUID_CERT_DIR:-$ROOT/distribution/cert}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Режим доверительной сборки:
# (можно переопределить переменной окружения DRUID_TRUSTSTORE_MODE)
MODE="${DRUID_TRUSTSTORE_MODE:-local+chain}"

#
# 1) Проверяем наличие локальных сертификатов в distribution/cert
#
ROOT_PEM="${CERT_DIR}/root.pem"
shopt -s nullglob
cert_files=()
if [[ -f "$ROOT_PEM" ]]; then
  cert_files+=("$ROOT_PEM")
fi
for f in "${CERT_DIR}"/*.crt; do
  cert_files+=("$f")
done
shopt -u nullglob

want_local=false
want_chain=false

case "$MODE" in
  local) want_local=true ;;
  chain) want_chain=true ;;
  local+chain) want_local=true; want_chain=true ;;
  auto)
    if (( ${#cert_files[@]} > 0 )); then
      want_local=true
      want_chain=false
    else
      want_local=false
      want_chain=true
    fi
    ;;
  *)
    echo "ОШИБКА: неизвестный DRUID_TRUSTSTORE_MODE='$MODE' (ожидается local|chain|local+chain|auto)" >&2
    exit 2
    ;;
esac

if ! $want_local && ! $want_chain; then
  echo "ОШИБКА: неверный режим сборки truststore ($MODE)." >&2
  exit 2
fi

echo "=== Truststore сборка: MODE=$MODE (local=$want_local, chain=$want_chain) ==="

# Всегда пересоздаём truststore (чтобы не накапливать устаревшие cert'ы).
rm -f "${STORE}"

# 2) Импорт локальных сертификатов (если включено)
if "$want_local" && (( ${#cert_files[@]} > 0 )); then
  echo "[1/x] Импорт локальных сертификатов из ${CERT_DIR} в truststore ${STORE} ..."
  echo "[local] local cert files count=${#cert_files[@]}"
  idx=0
  for cert in "${cert_files[@]}"; do
    idx=$((idx + 1))
    alias_name="druid-local-${idx}"

    keytool -importcert \
      -alias "${alias_name}" \
      -file "${cert}" \
      -keystore "${STORE}" \
      -storetype PKCS12 \
      -storepass "${PASS}" \
      -noprompt >/dev/null

    subject="$(openssl x509 -in "${cert}" -noout -subject 2>/dev/null | sed 's/^subject=//' || true)"
    echo "  импортирован: ${alias_name} -> ${subject}"
  done
fi

# 3) Импорт цепочки сертификатов (если включено)
if "$want_chain"; then
  CHAIN_RAW_FILE="${TMP_DIR}/chain.txt"
  CHAIN_PREFIX="${TMP_DIR}/cert"
  S_CLIENT_ERR_FILE="${TMP_DIR}/s_client.err"
  rm -f "$S_CLIENT_ERR_FILE" || true
  : > "$S_CLIENT_ERR_FILE"

  echo "[2/x] Получение цепочки сертификатов с ${HOST}:${PORT} ..."
  set +e
  openssl s_client \
    -connect "${HOST}:${PORT}" \
    -servername "${HOST}" \
    -showcerts \
    </dev/null > "${CHAIN_RAW_FILE}" 2> "$S_CLIENT_ERR_FILE"
  s_client_rc=$?
  set -e
  echo "[chain] openssl s_client rc=${s_client_rc}"
  if [[ "$s_client_rc" -ne 0 ]]; then
    echo "[chain] openssl s_client stderr (first 20 lines) from ${S_CLIENT_ERR_FILE}:"
    sed -n '1,20p' "$S_CLIENT_ERR_FILE" || true
  fi

  echo "[3/x] Извлечение PEM-сертификатов из chain..."
  awk '
    /BEGIN CERTIFICATE/ {
      in_cert = 1
      cert_index++
      file = sprintf("'"${CHAIN_PREFIX}"'-%02d.pem", cert_index)
    }
    in_cert { print > file }
    /END CERTIFICATE/ {
      in_cert = 0
      close(file)
    }
  ' "${CHAIN_RAW_FILE}"

  shopt -s nullglob
  chain_cert_files=( "${CHAIN_PREFIX}"-*.pem )
  shopt -u nullglob
  echo "[chain] extracted cert pem files count=${#chain_cert_files[@]}"
  if (( ${#chain_cert_files[@]} > 0 )); then
    echo "[4/x] Импорт сертификатов (chain) в ${STORE} ..."
    idx=0
    for cert in "${chain_cert_files[@]}"; do
      idx=$((idx + 1))
      alias_name="druid-chain-${idx}"

      keytool -importcert \
        -alias "${alias_name}" \
        -file "${cert}" \
        -keystore "${STORE}" \
        -storetype PKCS12 \
        -storepass "${PASS}" \
        -noprompt >/dev/null

      subject="$(openssl x509 -in "${cert}" -noout -subject | sed 's/^subject=//' || true)"
      echo "  импортирован: ${alias_name} -> ${subject}"
    done
  else
    echo "ОШИБКА: из TLS-цепочки ${HOST}:${PORT} не удалось извлечь сертификаты. openssl_rc=${s_client_rc} raw_file=${CHAIN_RAW_FILE}" >&2
    if [[ "$MODE" == "chain" || "$MODE" == "auto" ]]; then
      exit 1
    else
      echo "Продолжаем без chain (режим=$MODE)." >&2
    fi
  fi
fi

echo "[x] Проверка содержимого truststore ..."
keytool -list -keystore "${STORE}" -storetype PKCS12 -storepass "${PASS}"

cat <<EOF

Готово.
Перед запуском парсера задайте переменные окружения:

  export DRUID_TRUST_STORE_PATH="$(pwd)/${STORE}"
  export DRUID_TRUST_STORE_PASSWORD="${PASS}"
  export DRUID_TRUST_STORE_TYPE="PKCS12"

После этого запускайте приложение как обычно (например: ./gradlew run).
EOF

