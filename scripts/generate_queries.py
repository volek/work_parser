#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Universal query generator based on strategy manifest."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate strategy SQL queries using a manifest."
    )
    parser.add_argument(
        "--manifest",
        default="scripts/query-manifest.json",
        help="Path to manifest JSON (default: scripts/query-manifest.json)",
    )
    parser.add_argument(
        "--strategy",
        action="append",
        dest="strategies",
        help="Strategy name from manifest (can be passed multiple times). Default: all",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Do not write files; fail if target files differ from generated output",
    )
    return parser.parse_args()


def apply_replacements(content: str, replacements: list[list[str]]) -> str:
    for old, new in replacements:
        content = content.replace(old, new)
    return content


def collect_sql_files(source_dir: Path) -> list[Path]:
    return sorted(p for p in source_dir.rglob("*.sql") if p.is_file())


def run_strategy(
    repo_root: Path,
    name: str,
    cfg: dict,
    check_only: bool,
) -> tuple[int, int, int]:
    source_dir = repo_root / cfg["source_dir"]
    target_dir = repo_root / cfg["target_dir"]
    clean_target = bool(cfg.get("clean_target", False))
    exclude_files = set(cfg.get("exclude_files", []))
    replacements = cfg.get("replacements", [])

    if not source_dir.exists():
        raise FileNotFoundError(f"[{name}] source_dir not found: {source_dir}")

    source_files = [
        p for p in collect_sql_files(source_dir)
        if p.name not in exclude_files
    ]
    if not source_files:
        print(f"[{name}] no SQL files in {source_dir}")
        return 0, 0, 0

    if clean_target and not check_only and source_dir != target_dir:
        for old_file in collect_sql_files(target_dir):
            old_file.unlink()

    total = 0
    changed = 0
    mismatched = 0

    for src in source_files:
        rel = src.relative_to(source_dir)
        dst = target_dir / rel

        content = src.read_text(encoding="utf-8")
        generated = apply_replacements(content, replacements)
        total += 1

        if check_only:
            if not dst.exists():
                mismatched += 1
                print(f"[{name}] MISSING {dst}")
                continue
            current = dst.read_text(encoding="utf-8")
            if current != generated:
                mismatched += 1
                print(f"[{name}] DIFF {dst}")
            continue

        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            current = dst.read_text(encoding="utf-8")
            if current != generated:
                changed += 1
        else:
            changed += 1
        dst.write_text(generated, encoding="utf-8", newline="\n")

    return total, changed, mismatched


def main() -> int:
    args = parse_args()

    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent
    manifest_path = (repo_root / args.manifest).resolve()

    if not manifest_path.exists():
        print(f"Manifest not found: {manifest_path}", file=sys.stderr)
        return 2

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    strategies_cfg = manifest.get("strategies", {})
    if not strategies_cfg:
        print("Manifest contains no strategies.", file=sys.stderr)
        return 2

    selected = args.strategies or list(strategies_cfg.keys())
    unknown = [s for s in selected if s not in strategies_cfg]
    if unknown:
        print(f"Unknown strategies: {', '.join(unknown)}", file=sys.stderr)
        return 2

    total_files = 0
    total_changed = 0
    total_mismatched = 0

    mode = "CHECK" if args.check else "GENERATE"
    print(f"Mode: {mode}")
    print(f"Manifest: {manifest_path}")

    for strategy in selected:
        cfg = strategies_cfg[strategy]
        total, changed, mismatched = run_strategy(
            repo_root=repo_root,
            name=strategy,
            cfg=cfg,
            check_only=args.check,
        )
        total_files += total
        total_changed += changed
        total_mismatched += mismatched
        if args.check:
            print(f"[{strategy}] files={total} mismatched={mismatched}")
        else:
            print(f"[{strategy}] files={total} changed={changed}")

    if args.check and total_mismatched > 0:
        print(f"FAILED: {total_mismatched} file(s) differ from generated output.")
        return 1

    if args.check:
        print(f"OK: all {total_files} file(s) match manifest generation rules.")
    else:
        print(f"Done: processed={total_files}, changed={total_changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
