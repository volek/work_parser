#!/usr/bin/env python3
"""
Извлекает из logs/*.log и query-results/*.txt все TEMP_PERF-метрики
и выводит min/max/avg (и при необходимости sum/count) по каждой стратегии.
"""
import re
import os
from pathlib import Path
from collections import defaultdict

def parse_kv(line: str) -> dict:
    """Parse TEMP_PERF key=value pairs from a log line."""
    d = {}
    for part in line.split("|"):
        if "=" in part:
            k, v = part.split("=", 1)
            k = k.strip()
            v = v.strip()
            try:
                if "." in v:
                    d[k] = float(v)
                else:
                    d[k] = int(v)
            except ValueError:
                d[k] = v
    return d

def stats(vals):
    if not vals:
        return None
    vals = [float(x) for x in vals if x is not None]
    if not vals:
        return None
    return {
        "min": round(min(vals), 4),
        "max": round(max(vals), 4),
        "avg": round(sum(vals) / len(vals), 4),
        "sum": round(sum(vals), 2),
        "n": len(vals),
    }

def main():
    base = Path(__file__).resolve().parent.parent
    logs_dir = base / "logs"
    query_dir = base / "query-results"

    # Map log file -> strategy name
    log_files = {
        "default_ingest.log": "default",
        "eav_ingest.log": "eav",
        "hybrid_ingest.log": "hybrid",
        "combined_ingest_warm210.log": "combined",
        "compcom_ingest_warm210.log": "compcom",
    }

    parse_one = defaultdict(list)   # strategy -> list of dicts
    file_parse = defaultdict(list)
    batch_submit = defaultdict(list)
    ingest_summary = defaultdict(list)  # strategy -> list (per datasource)
    ingest_datasource = defaultdict(list)
    ingest_stage_summary = defaultdict(list)

    for log_name, strategy in log_files.items():
        path = logs_dir / log_name
        if not path.exists():
            continue
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if "TEMP_PERF|" not in line:
                    continue
                idx = line.find("TEMP_PERF|")
                payload = line[idx:]
                d = parse_kv(payload)
                op = d.get("operation")
                if op == "parse_one":
                    parse_one[strategy].append(d)
                elif op == "file_parse":
                    d["strategy"] = strategy
                    file_parse[strategy].append(d)
                elif op == "batch_submit":
                    d["strategy"] = d.get("data_source", strategy)
                    batch_submit[strategy].append(d)
                elif op == "ingest_summary":
                    ingest_summary[strategy].append(d)
                elif op == "ingest_datasource":
                    ingest_datasource[strategy].append(d)
                elif op == "ingest_stage_summary":
                    ingest_stage_summary[strategy].append(d)

    # Query results
    query_files = {"default.txt": "default", "eav.txt": "eav", "hybrid.txt": "hybrid", "combined.txt": "combined", "compcom.txt": "compcom"}
    sql_request = defaultdict(list)
    query_summary = defaultdict(list)

    for qname, strategy in query_files.items():
        path = query_dir / qname
        if not path.exists():
            continue
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if "TEMP_PERF|" not in line:
                    continue
                idx = line.find("TEMP_PERF|")
                payload = line[idx:]
                d = parse_kv(payload)
                op = d.get("operation")
                if op == "sql_request":
                    sql_request[strategy].append(d)
                elif op == "query_summary":
                    query_summary[strategy].append(d)

    # Output as structured text for embedding in report
    out = []

    out.append("=== PARSE (parse_one + file_parse) per strategy ===\n")
    for strategy in ["default", "eav", "hybrid", "combined", "compcom"]:
        out.append(f"--- {strategy} ---")
        # parse_one
        po = parse_one[strategy]
        if po:
            for key in ["json_chars", "json_bytes", "deserialize_ns", "deserialize_ms", "total_ns", "total_ms", "throughput_mb_s"]:
                vals = [x[key] for x in po if key in x and isinstance(x[key], (int, float))]
                if vals:
                    s = stats(vals)
                    out.append(f"  parse_one.{key}: min={s['min']} max={s['max']} avg={s['avg']} n={s['n']}")
        fp = file_parse[strategy]
        if fp:
            for key in ["file_size_bytes", "read_ns", "read_ms", "parse_ns", "parse_ms", "file_total_ns", "file_total_ms"]:
                vals = [x[key] for x in fp if key in x and isinstance(x[key], (int, float))]
                if vals:
                    s = stats(vals)
                    out.append(f"  file_parse.{key}: min={s['min']} max={s['max']} avg={s['avg']} n={s['n']}")
        out.append("")

    out.append("=== INGEST (batch_submit + ingest_summary + ingest_datasource + ingest_stage_summary) ===\n")
    for strategy in ["default", "eav", "hybrid", "combined", "compcom"]:
        out.append(f"--- {strategy} ---")
        bs = batch_submit[strategy]
        if bs:
            for key in ["batch_index", "batch_count", "batch_records", "spec_build_ns", "spec_build_ms", "http_round_trip_ns", "http_round_trip_ms", "response_decode_ns", "response_decode_ms", "batch_total_ns", "batch_total_ms"]:
                vals = [x[key] for x in bs if key in x and isinstance(x[key], (int, float))]
                if vals:
                    s = stats(vals)
                    out.append(f"  batch_submit.{key}: min={s['min']} max={s['max']} avg={s['avg']} n={s['n']}")
        for ds in ingest_summary[strategy]:
            ds_name = ds.get("data_source", "?")
            out.append(f"  ingest_summary[{ds_name}]: records_total={ds.get('records_total')} batches_total={ds.get('batches_total')} batch_size_config={ds.get('batch_size_config')}")
            out.append(f"    split_ms={ds.get('split_ms')} sum_spec_build_ms={ds.get('sum_spec_build_ms')} sum_http_round_trip_ms={ds.get('sum_http_round_trip_ms')} sum_response_decode_ms={ds.get('sum_response_decode_ms')}")
            out.append(f"    min_batch_total_ms={ds.get('min_batch_total_ms')} max_batch_total_ms={ds.get('max_batch_total_ms')} ingest_total_ms={ds.get('ingest_total_ms')} avg_batch_ms={ds.get('avg_batch_ms')} records_per_sec={ds.get('records_per_sec')} batches_per_sec={ds.get('batches_per_sec')}")
        for row in ingest_datasource[strategy]:
            out.append(f"  ingest_datasource: data_source={row.get('data_source')} records_count={row.get('records_count')} ingest_ms={row.get('ingest_ms')}")
        for row in ingest_stage_summary[strategy]:
            out.append(f"  ingest_stage_summary: ingest_ms={row.get('ingest_ms')} warm_variables_limit_effective={row.get('warm_variables_limit_effective')}")
        out.append("")

    out.append("=== SELECT / QUERY (sql_request + query_summary) per strategy ===\n")
    for strategy in ["default", "eav", "hybrid", "combined", "compcom"]:
        out.append(f"--- {strategy} ---")
        sr = sql_request[strategy]
        if sr:
            for key in ["sql_chars", "rows_count", "http_round_trip_ns", "http_round_trip_ms", "body_read_ns", "body_read_ms", "json_decode_ns", "json_decode_ms", "total_ns", "total_ms", "avg_per_row_ms", "response_body_chars"]:
                vals = [x[key] for x in sr if key in x and isinstance(x[key], (int, float))]
                if vals:
                    s = stats(vals)
                    out.append(f"  sql_request.{key}: min={s['min']} max={s['max']} avg={s['avg']} n={s['n']}")
        qs = query_summary[strategy]
        if qs:
            for key in ["raw_sql_chars", "prepared_sql_chars", "read_ns", "read_ms", "prepare_ns", "prepare_ms", "druid_exec_ns", "druid_exec_ms", "print_ns", "print_ms", "rows_count", "query_command_total_ns", "query_command_total_ms", "avg_per_row_ms"]:
                vals = [x[key] for x in qs if key in x and isinstance(x[key], (int, float))]
                if vals:
                    s = stats(vals)
                    out.append(f"  query_summary.{key}: min={s['min']} max={s['max']} avg={s['avg']} n={s['n']}")
        out.append("")

    result = "\n".join(out)
    print(result)
    # Also write to file for reference
    (base / "docs" / "metrics_extract_output.txt").write_text(result, encoding="utf-8")

if __name__ == "__main__":
    main()
