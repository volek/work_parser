#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Backward-compatible wrapper for compcom query generation.

Use `scripts/generate_queries.py` for unified generation of all strategies.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    generator = script_dir / "generate_queries.py"

    cmd = [sys.executable, str(generator), "--strategy", "compcom"]
    completed = subprocess.run(cmd, check=False)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
