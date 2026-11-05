"""End-to-end smoke test for the Yobareta Cloud Run backend.

What it tests:
1. Local deterministic matcher unit tests (SudachiPy + long vowel normalization + tiers).
2. Optional live API test against either a local server (http://127.0.0.1:8080) or deployed
   Cloud Run URL (pass via --url https://yobareta-api-xxxx.asia-northeast1.run.app):
   - Generates a synthetic 16 kHz mono LINEAR16 WAV announcement in memory (using Cloud TTS
     Chirp 3 HD ja-JP if credentials are available, or a sine-wave test tone fallback).
   - Calls GET /warm and POST /recognize and prints the JSON response.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import math
import struct
import sys
import urllib.request
import wave

from matcher import evaluate_transcript


def make_test_wav_16khz(duration_sec: float = 2.0) -> bytes:
    """Creates a valid 16 kHz mono 16-bit PCM WAV in memory."""
    sample_rate = 16000
    num_samples = int(sample_rate * duration_sec)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        frames = bytearray()
        for i in range(num_samples):
            val = int(1000 * math.sin(2.0 * math.pi * 440.0 * (i / sample_rate)))
            frames.extend(struct.pack("<h", val))
        wf.writeframes(bytes(frames))
    return buf.getvalue()


def synthesize_tts_chirp3_hd_ja(text: str) -> bytes | None:
    """Attempts to synthesize 16 kHz LINEAR16 audio via Cloud TTS Chirp 3 HD (ja-JP)."""
    try:
        from google.cloud import texttospeech
        client = texttospeech.TextToSpeechClient()
        synthesis_input = texttospeech.SynthesisInput(text=text)
        voice = texttospeech.VoiceSelectionParams(
            language_code="ja-JP",
            name="ja-JP-Chirp3-HD-Aoede",
        )
        audio_config = texttospeech.AudioConfig(
            audio_encoding=texttospeech.AudioEncoding.LINEAR16,
            sample_rate_hertz=16000,
        )
        response = client.synthesize_speech(
            input=synthesis_input,
            voice=voice,
            audio_config=audio_config,
        )
        return response.audio_content
    except Exception as exc:
        print(f"[info] Cloud TTS synthesis skipped ({type(exc).__name__}: {exc}); using local test WAV.")
        return None


def run_local_matcher_checks() -> None:
    print("=== 1. Testing Stage 1 Deterministic Reading Matcher ===")
    cases = [
        {
            "label": "Hospital Full Name (東海林太郎 -> ショージタロウ)",
            "transcript": "ショージタロウ様、3番診察室へお入りください",
            "full_name_readings": ["ショウジタロウ", "トウカイリンタロウ"],
            "surname_readings": ["ショウジ", "トウカイリン"],
            "ticket_number": None,
            "expected_tier": "called",
        },
        {
            "label": "Surname-Only Cap (佐藤 -> サトー must stay 'possible', never 'called')",
            "transcript": "サトー様、2番窓口へお越しください",
            "full_name_readings": ["サトウタロウ"],
            "surname_readings": ["サトウ"],
            "ticket_number": None,
            "expected_tier": "possible",
        },
        {
            "label": "Cafe Order Number (#305)",
            "transcript": "ご注文番号305番のお客様、受け取りカウンターまでお越しください",
            "full_name_readings": ["ショウジタロウ"],
            "surname_readings": ["ショウジ"],
            "ticket_number": "305",
            "expected_tier": "called",
        },
        {
            "label": "Room Number False-Alarm Guard (Ticket #3 vs Exam Room 3)",
            "transcript": "スズキ様、3番診察室へお入りください",
            "full_name_readings": ["ショウジタロウ"],
            "surname_readings": ["ショウジ"],
            "ticket_number": "3",
            "expected_tier": "none",
        },
        {
            "label": "Re-call Escalation",
            "transcript": "再度お呼びいたします。ショウジタロウ様、3番診察室へお入りください",
            "full_name_readings": ["ショウジタロウ"],
            "surname_readings": ["ショウジ"],
            "ticket_number": None,
            "expected_tier": "recall",
        },
    ]

    for c in cases:
        res = evaluate_transcript(
            transcript=c["transcript"],
            full_name_readings=c["full_name_readings"],
            surname_readings=c["surname_readings"],
            ticket_number=c["ticket_number"],
        )
        ok = res.tier == c["expected_tier"]
        status_str = "PASS" if ok else f"FAIL (got {res.tier}, expected {c['expected_tier']})"
        print(f"  [{status_str}] {c['label']}")
        print(f"         -> tier={res.tier}, score={res.score}, dest='{res.destination}', instr='{res.instruction_plain}'")
        if not ok:
            sys.exit(1)
    print("All local matcher checks passed!\n")


def run_endpoint_check(base_url: str, id_token: str | None, app_check_token: str | None) -> None:
    base = base_url.rstrip("/")
    print(f"=== 2. Testing Live Endpoints at {base} ===")

    headers = {"Content-Type": "application/json"}
    if id_token:
        headers["Authorization"] = f"Bearer {id_token}"
    if app_check_token:
        headers["X-Firebase-AppCheck"] = app_check_token

    # 1. Warm check
    warm_req = urllib.request.Request(f"{base}/warm", headers=headers, method="GET")
    with urllib.request.urlopen(warm_req, timeout=15) as resp:
        print("GET /warm ->", resp.read().decode("utf-8"))

    # 2. Synthesize or generate audio for POST /recognize
    tts_phrase = "東海林太郎様、3番診察室へお入りください。"
    audio_bytes = synthesize_tts_chirp3_hd_ja(tts_phrase) or make_test_wav_16khz()

    payload = {
        "audio_b64": base64.b64encode(audio_bytes).decode("ascii"),
        "full_name_readings": ["ショウジタロウ", "トウカイリンタロウ"],
        "surname_readings": ["ショウジ", "トウカイリン"],
        "kanji_name": "東海林太郎",
        "ticket_number": "305",
        "venue_mode": "hospital",
    }
    rec_req = urllib.request.Request(
        f"{base}/recognize",
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(rec_req, timeout=20) as resp:
        body = json.loads(resp.read().decode("utf-8"))
        print("POST /recognize ->", json.dumps(body, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Smoke test Yobareta backend")
    parser.add_argument("--url", help="Optional base URL of running server (e.g. http://127.0.0.1:8080)")
    parser.add_argument("--id-token", default=None, help="Optional Firebase Auth ID token")
    parser.add_argument("--app-check-token", default=None, help="Optional Firebase App Check token")
    args = parser.parse_args()

    run_local_matcher_checks()
    if args.url:
        run_endpoint_check(args.url, args.id_token, args.app_check_token)
