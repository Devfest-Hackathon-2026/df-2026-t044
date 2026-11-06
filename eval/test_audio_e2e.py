#!/usr/bin/env python3
"""End-to-End Audio Test Harness.

Sends each generated .wav audio clip directly to the /recognize API
(either running locally or on Cloud Run) and verifies:
1. End-to-end latency (upload + STT + Matcher)
2. Resulting tier (called / possible / recall / none) against eval/calls.csv
3. Destination extraction accuracy

Usage:
    python eval/test_audio_e2e.py [--url http://127.0.0.1:8080]
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path
import urllib.request
import urllib.error

# Profiles matching eval/score.py
PROFILES = {
    "A": {
        "kanji_name": "東海林 太郎",
        "full_name_readings": ["ショウジ タロウ", "トウカイリン タロウ", "Shouji Tarou", "Tarou Shouji"],
        "surname_readings": ["ショウジ", "トウカイリン", "Shouji"],
        "ticket_number": "305",
    },
    "B": {
        "kanji_name": "Alex Smith",
        "full_name_readings": ["Alex Smith", "アレックス スミス"],
        "surname_readings": ["Smith", "スミス"],
        "ticket_number": "A-12",
    },
    "C": {
        "kanji_name": "佐藤 太郎",
        "full_name_readings": ["サトウ タロウ", "Satou Tarou", "Tarou Satou"],
        "surname_readings": ["サトウ", "Satou"],
        "ticket_number": "",
    },
}


def main():
    parser = argparse.ArgumentParser(description="Run E2E tests using generated audio clips.")
    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8080",
        help="Base URL of Yobareta backend (e.g. http://127.0.0.1:8080 or Cloud Run URL)",
    )
    args = parser.parse_args()

    eval_dir = Path(__file__).resolve().parent
    audio_dir = eval_dir / "audio"
    calls_csv = eval_dir / "calls.csv"

    if not audio_dir.exists() or not any(audio_dir.glob("*.wav")):
        print(f"Error: No .wav audio clips found in {audio_dir}.")
        print("Please run `python eval/generate_audio.py` first to generate clips.")
        return 1

    with open(calls_csv, mode="r", encoding="utf-8-sig") as f:
        calls = list(csv.DictReader(f))

    recognize_url = f"{args.url.rstrip('/')}/recognize"
    print(f"Targeting: {recognize_url}")
    print("=" * 86)
    print(f"{'ID':<5} {'Cat':<15} {'Audio':<10} {'Tier':<10} {'Expected':<15} {'Latency':<9} {'Status'}")
    print("-" * 86)

    passed = 0
    total = 0
    latencies = []

    for row in calls:
        call_id = row["id"]
        wav_path = audio_dir / f"{call_id}.wav"
        if not wav_path.exists():
            continue

        total += 1
        prof = PROFILES[row["profile"]]
        acceptable = {t.strip() for t in row["acceptable_tiers"].split("|")}
        exp_dest = (row.get("expected_destination") or "").strip()

        with open(wav_path, "rb") as af:
            audio_bytes = af.read()

        # Build multipart/form-data boundary payload
        boundary = "----WebKitFormBoundaryYobaretaAudioTest7MA4YWxkTrZu0gW"
        meta_dict = {
            "kanji_name": prof["kanji_name"],
            "full_name_readings": prof["full_name_readings"],
            "surname_readings": prof["surname_readings"],
            "ticket_number": prof["ticket_number"],
            "venue_mode": row.get("setting", "auto"),
            "language_mode": row.get("language", "auto"),
            "previously_called": False,
        }

        body = bytearray()
        # metadata part
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(b'Content-Disposition: form-data; name="metadata"\r\n\r\n')
        body.extend(json.dumps(meta_dict).encode("utf-8"))
        body.extend(b"\r\n")

        # audio part
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="audio"; filename="{wav_path.name}"\r\n'.encode("utf-8"))
        body.extend(b"Content-Type: audio/wav\r\n\r\n")
        body.extend(audio_bytes)
        body.extend(b"\r\n")
        body.extend(f"--{boundary}--\r\n".encode("utf-8"))

        req = urllib.request.Request(
            recognize_url,
            data=bytes(body),
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST",
        )

        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                elapsed_ms = int((time.perf_counter() - t0) * 1000)
                latencies.append(elapsed_ms)
                data = json.loads(resp.read().decode("utf-8"))
                res_tier = data.get("tier", "none")
                dest = data.get("destination", "")

                tier_ok = res_tier in acceptable
                dest_ok = (not exp_dest) or (exp_dest.lower() in dest.lower())
                ok = tier_ok and dest_ok

                status_str = "PASS" if ok else "FAIL"
                if ok:
                    passed += 1

                print(
                    f"{call_id:<5} {row['category'][:13]:<15} {wav_path.name:<10} {res_tier:<10} {row['acceptable_tiers']:<15} {elapsed_ms}ms    {status_str}"
                )

        except urllib.error.HTTPError as e:
            err_msg = e.read().decode("utf-8", errors="ignore")
            print(f"{call_id:<5} {row['category'][:13]:<15} {wav_path.name:<10} ERROR      {row['acceptable_tiers']:<15} {e.code}     FAIL ({err_msg[:20]})")
        except Exception as e:
            print(f"{call_id:<5} {row['category'][:13]:<15} {wav_path.name:<10} ERROR      {row['acceptable_tiers']:<15} ---       FAIL ({type(e).__name__})")

    print("-" * 86)
    med_lat = f"{int(sum(latencies)/len(latencies))}ms" if latencies else "N/A"
    print(f"Summary: {passed}/{total} audio tests passed ({100*passed/total if total else 0:.0f}%) | Avg Latency: {med_lat}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
