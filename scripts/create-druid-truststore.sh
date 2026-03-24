#!/usr/bin/env bash
set -euo pipefail

# Создаёт PKCS12 truststore из полной TLS-цепочки целевого хоста.
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

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CHAIN_RAW_FILE="${TMP_DIR}/chain.txt"
CHAIN_PREFIX="${TMP_DIR}/cert"

echo "[1/5] Получение цепочки сертификатов с ${HOST}:${PORT} ..."
openssl s_client \
  -connect "${HOST}:${PORT}" \
  -servername "${HOST}" \
  -showcerts \
  </dev/null 2>/dev/null > "${CHAIN_RAW_FILE}"

echo "[2/5] Извлечение PEM-сертификатов ..."
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

cert_files=( "${CHAIN_PREFIX}"-*.pem )
if [ ! -e "${cert_files[0]}" ]; then
  echo "ОШИБКА: из серверной цепочки не удалось извлечь сертификаты."
  exit 1
fi

echo "[3/5] Пересоздание truststore ${STORE} ..."
rm -f "${STORE}"

echo "[4/5] Импорт сертификатов в ${STORE} ..."
idx=0
for cert in "${cert_files[@]}"; do
  idx=$((idx + 1))
  alias_name="druid-chain-${idx}"

  keytool -importcert \
    -alias "${alias_name}" \
    -file "${cert}" \
    -keystore "${STORE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -noprompt >/dev/null

  subject="$(openssl x509 -in "${cert}" -noout -subject | sed 's/^subject=//')"
  echo "  импортирован: ${alias_name} -> ${subject}"
done

echo "[5/5] Проверка содержимого truststore ..."
keytool -list -keystore "${STORE}" -storetype PKCS12 -storepass "${PASS}"

cat <<EOF

Готово.
Перед запуском парсера задайте переменные окружения:

  export DRUID_TRUST_STORE_PATH="$(pwd)/${STORE}"
  export DRUID_TRUST_STORE_PASSWORD="${PASS}"
  export DRUID_TRUST_STORE_TYPE="PKCS12"

После этого запускайте приложение как обычно (например: ./gradlew run).
EOF

