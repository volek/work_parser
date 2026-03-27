#!/usr/bin/env python3
"""
Проверка доступности Druid endpoint'ов и TLS (verify on/off).

Установка зависимостей (для Python 3.8):
  python3 -m pip install "requests<2.32"

Запуск:
  python3 scripts/check_druid_tls.py

Опционально через переменные окружения:
  DRUID_HOST=omltd-abyss-sdp2-druid-10.opsmon.sbt
  DRUID_USERNAME=admin
  DRUID_PASSWORD=admin
  DRUID_SUPPRESS_INSECURE_WARNING=true
  python3 scripts/check_druid_tls.py
"""

from __future__ import annotations

import os
from typing import Iterable

try:
    import requests
    from requests.auth import HTTPBasicAuth
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        "Missing dependency 'requests'. Install compatible version for Python 3.8:\n"
        "  python3 -m pip install \"requests<2.32\""
    ) from exc


def env(name: str, default: str) -> str:
    value = os.getenv(name)
    return value if value is not None and value.strip() else default


HOST = env("DRUID_HOST", "omltd-abyss-sdp2-druid-10.opsmon.sbt")
USER = env("DRUID_USERNAME", "admin")
PWD = env("DRUID_PASSWORD", "admin")
TIMEOUT = float(env("DRUID_TIMEOUT_SECONDS", "15"))
SUPPRESS_INSECURE_WARNING = env("DRUID_SUPPRESS_INSECURE_WARNING", "true").lower() in {
    "1",
    "true",
    "yes",
    "on",
}

URLS = [
    f"https://{HOST}:9088/status/health",  # router
    f"https://{HOST}:8281/druid/coordinator/v1/isLeader",  # coordinator
    f"https://{HOST}:8290/druid/indexer/v1/isLeader",  # overlord
    f"https://{HOST}:8282/druid/v2/sql",  # broker SQL
]


def check(url: str, verify: bool) -> None:
    try:
        if url.endswith("/druid/v2/sql"):
            response = requests.post(
                url,
                json={"query": "SELECT 1"},
                auth=HTTPBasicAuth(USER, PWD),
                timeout=TIMEOUT,
                verify=verify,
            )
        else:
            response = requests.get(
                url,
                auth=HTTPBasicAuth(USER, PWD),
                timeout=TIMEOUT,
                verify=verify,
            )
        print(
            f"[OK] {url} verify={verify} -> {response.status_code}, "
            f"body={response.text[:200]}"
        )
    except Exception as exc:  # noqa: BLE001
        print(f"[ERR] {url} verify={verify} -> {type(exc).__name__}: {exc}")


def run(urls: Iterable[str]) -> None:
    if SUPPRESS_INSECURE_WARNING:
        try:
            import urllib3

            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        except Exception as exc:  # noqa: BLE001
            print(
                f"[WARN] Failed to disable InsecureRequestWarning: "
                f"{type(exc).__name__}: {exc}"
            )

    print("=== verify=True ===")
    for url in urls:
        check(url, True)

    print("\n=== verify=False (insecure) ===")
    for url in urls:
        check(url, False)


if __name__ == "__main__":
    run(URLS)
