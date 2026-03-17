# -*- coding: utf-8 -*-
"""Generate compcom query files from combined: process_main -> process_main_compact, update headers."""
import os
import re

COMBINED_DIR = os.path.join(os.path.dirname(__file__), "..", "query", "combined")
COMPCOM_DIR = os.path.join(os.path.dirname(__file__), "..", "query", "compcom")

os.makedirs(COMPCOM_DIR, exist_ok=True)

for name in sorted(os.listdir(COMBINED_DIR)):
    if not name.endswith(".sql"):
        continue
    path = os.path.join(COMBINED_DIR, name)
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    content = content.replace("process_main", "process_main_compact")
    content = content.replace("Combined (Tiered Hot/Warm/Cold)", "Compcom (Compact combined, no cold blob)")
    content = content.replace("Файл: `combined/", "Файл: `compcom/")
    content = content.replace(
        "горячие поля в основной записи + индексируемые переменные + cold JSON-поля для редких доступов",
        "горячие поля в основной записи + индексируемые переменные, без cold blob"
    )
    content = content.replace("Выбор источника данных: process_main_compact.", "Выбор источника данных: process_main_compact.")
    out_path = os.path.join(COMPCOM_DIR, name)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print(out_path)

print("Done. compcom queries:", len([f for f in os.listdir(COMPCOM_DIR) if f.endswith(".sql")]))
