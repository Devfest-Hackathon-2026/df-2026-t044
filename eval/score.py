#!/usr/bin/env python3
"""Score a Yobareta vs Google Live Transcribe head-to-head run.

Usage:
    python eval/score.py eval/results.csv

Reads eval/calls.csv (the test script) and a filled-in copy of
eval/results_template.csv. Standard library only.
"""

from __future__ import annotations

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

FULL_ALERT = {"called", "recall"}
ANY_ALERT = FULL_ALERT | {"possible"}


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as f:
        return [row for row in csv.DictReader(f) if any((v or "").strip() for v in row.values())]


def is_yes(value: str | None) -> bool:
    return (value or "").strip().lower() in {"y", "yes", "1", "true"}


def parse_float(value: str | None) -> float | None:
    try:
        return float((value or "").strip())
    except ValueError:
        return None


def pct(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "n/a"
    return f"{numerator}/{denominator} ({100 * numerator / denominator:.0f}%)"


def median_str(values: list[float]) -> str:
    return f"{statistics.median(values):.2f} s (n={len(values)})" if values else "n/a"


def main() -> int:
    here = Path(__file__).resolve().parent
    results_path = Path(sys.argv[1]) if len(sys.argv) > 1 else here / "results.csv"
    calls = {row["id"]: row for row in load_csv(here / "calls.csv")}
    results = [
        row for row in load_csv(results_path)
        if (row.get("yob_tier") or "").strip() or (row.get("lt_buzzed") or "").strip()
    ]
    if not results:
        print(f"No filled-in rows found in {results_path}")
        return 1

    counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    yob_latencies: list[float] = []
    lt_latencies: list[float] = []
    dest_ok = dest_total = 0
    row_failures: list[str] = []

    for row in results:
        call = calls.get(row["id"])
        if call is None:
            print(f"warning: unknown id {row['id']} (skipped)")
            continue

        category = call["category"]
        yob_tier = (row.get("yob_tier") or "").strip().lower() or "none"
        lt_buzzed = is_yes(row.get("lt_buzzed"))

        c = counts[category]
        c["n"] += 1
        c["yob_full"] += yob_tier in FULL_ALERT
        c["yob_any"] += yob_tier in ANY_ALERT
        c["yob_possible"] += yob_tier == "possible"
        c["lt"] += lt_buzzed

        acceptable = {t.strip() for t in call["acceptable_tiers"].split("|")}
        if yob_tier not in acceptable:
            row_failures.append(
                f"  {row['id']} (run {row.get('run', '?')}): got '{yob_tier}', expected {sorted(acceptable)} — {call['gap_tested']}"
            )

        if call["expected_destination"].strip() and yob_tier in FULL_ALERT:
            dest_total += 1
            dest_ok += is_yes(row.get("yob_destination_correct"))

        if (lat := parse_float(row.get("yob_latency_s"))) is not None:
            yob_latencies.append(lat)
        if (lat := parse_float(row.get("lt_latency_s"))) is not None:
            lt_latencies.append(lat)

    tc, so, ds, nc = (counts[k] for k in ("true_call", "surname_only_call", "distractor", "non_call"))

    print("=" * 72)
    print("Yobareta vs Live Transcribe — head-to-head summary")
    print("=" * 72)
    print(f"{'Metric':<44}{'Yobareta':<16}{'Live Transcribe':<16}")
    print("-" * 72)
    print(f"{'True calls detected (full alert / buzz)':<44}{pct(tc['yob_full'], tc['n']):<16}{pct(tc['lt'], tc['n']):<16}")
    print(f"{'Surname-only calls noticed (any alert)':<44}{pct(so['yob_any'], so['n']):<16}{pct(so['lt'], so['n']):<16}")
    print(f"{'Distractors: FALSE full alarms (lower=better)':<44}{pct(ds['yob_full'], ds['n']):<16}{pct(ds['lt'], ds['n']):<16}")
    print(f"{'Distractors: soft Possible alerts':<44}{pct(ds['yob_possible'], ds['n']):<16}{'(no tiers)':<16}")
    print(f"{'Non-calls: any alert (lower=better)':<44}{pct(nc['yob_any'], nc['n']):<16}{pct(nc['lt'], nc['n']):<16}")
    print(f"{'Correct destination shown':<44}{pct(dest_ok, dest_total):<16}{'(not offered)':<16}")
    print(f"{'Median latency (end of name → buzz)':<44}{median_str(yob_latencies):<16}{median_str(lt_latencies):<16}")
    print("-" * 72)

    if row_failures:
        print("Yobareta rows outside the acceptable tiers (investigate these):")
        print("\n".join(row_failures))
    else:
        print("All Yobareta rows were within the acceptable tiers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
