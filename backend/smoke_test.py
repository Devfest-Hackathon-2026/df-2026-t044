"""End-to-end smoke test for the Yobareta Cloud Run backend (Japanese + English).

What it tests:
1. Local deterministic matcher unit tests (SudachiPy Japanese + English phonetic + tiers).
2. Optional live API test against either a local server (http://127.0.0.1:8080) or deployed
   Cloud Run URL (pass via --url https://yobareta-api-xxxx.asia-northeast1.run.app):
   - Generates synthetic 16 kHz mono LINEAR16 WAV announcements in Japanese and English.
   - Calls GET /warm and POST /recognize and prints the JSON responses.
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


def synthesize_tts_chirp3_hd(text: str, language_code: str = "ja-JP") -> bytes | None:
    """Attempts to synthesize 16 kHz LINEAR16 audio via Cloud TTS Chirp 3 HD."""
    try:
        from google.cloud import texttospeech
        client = texttospeech.TextToSpeechClient()
        synthesis_input = texttospeech.SynthesisInput(text=text)
        voice_name = (
            "ja-JP-Chirp3-HD-Aoede"
            if language_code.startswith("ja")
            else "en-US-Chirp3-HD-Aoede"
        )
        voice = texttospeech.VoiceSelectionParams(
            language_code=language_code,
            name=voice_name,
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
    print("=== 1. Testing Stage 1 Deterministic Matcher (Japanese + English) ===")
    cases = [
        {
            "label": "[JA] Hospital Full Name (東海林太郎 -> ショージタロウ)",
            "transcript": "ショージタロウ様、3番診察室へお入りください",
            "full_name_readings": ["ショウジタロウ", "トウカイリンタロウ"],
            "surname_readings": ["ショウジ", "トウカイリン"],
            "ticket_number": None,
            "expected_tier": "called",
        },
        {
            "label": "[JA] Surname-Only Cap (佐藤 -> サトー must stay 'possible', never 'called')",
            "transcript": "サトー様、2番窓口へお越しください",
            "full_name_readings": ["サトウタロウ"],
            "surname_readings": ["サトウ"],
            "ticket_number": None,
            "expected_tier": "possible",
        },
        {
            "label": "[EN] Hospital Full Name (Alex Smith -> Exam Room 3)",
            "transcript": "Mr. Alex Smith, please proceed to Exam Room 3.",
            "full_name_readings": ["Alex Smith", "アレックススミス"],
            "surname_readings": ["Smith", "スミス"],
            "ticket_number": None,
            "expected_tier": "called",
        },
        {
            "label": "[EN] Surname-Only Cap (Mr. Smith -> Counter 2 stays 'possible')",
            "transcript": "Mr. Smith, please come to Counter 2.",
            "full_name_readings": ["Alex Smith"],
            "surname_readings": ["Smith"],
            "ticket_number": None,
            "expected_tier": "possible",
        },
        {
            "label": "[EN] Cafe Order Number (#305)",
            "transcript": "Order number 305 is ready at the pickup counter.",
            "full_name_readings": ["Alex Smith"],
            "surname_readings": ["Smith"],
            "ticket_number": "305",
            "expected_tier": "called",
        },
        {
            "label": "[JA+EN] Room Number False-Alarm Guard (Ticket #3 vs Exam Room 3)",
            "transcript": "Mr. Johnson, please proceed to Exam Room 3.",
            "full_name_readings": ["Alex Smith"],
            "surname_readings": ["Smith"],
            "ticket_number": "3",
            "expected_tier": "none",
        },
        {
            "label": "[EN] Re-call Escalation",
            "transcript": "Final call for Alex Smith, please proceed to Exam Room 3.",
            "full_name_readings": ["Alex Smith"],
            "surname_readings": ["Smith"],
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
    print("All Japanese & English local matcher checks passed!\n")


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

    # 2. Japanese hospital announcement
    tts_ja = "東海林太郎様、3番診察室へお入りください。"
    audio_ja = synthesize_tts_chirp3_hd(tts_ja, "ja-JP") or make_test_wav_16khz()
    payload_ja = {
        "audio_b64": base64.b64encode(audio_ja).decode("ascii"),
        "full_name_readings": ["ショウジタロウ", "トウカイリンタロウ"],
        "surname_readings": ["ショウジ", "トウカイリン"],
        "kanji_name": "東海林太郎",
        "ticket_number": "305",
        "venue_mode": "hospital",
        "language_mode": "auto",
    }
    rec_req_ja = urllib.request.Request(
        f"{base}/recognize",
        data=json.dumps(payload_ja).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(rec_req_ja, timeout=20) as resp:
        body_ja = json.loads(resp.read().decode("utf-8"))
        print("POST /recognize [JA] ->", json.dumps(body_ja, indent=2, ensure_ascii=False))

    # 3. English hospital announcement
    tts_en = "Mr. Alex Smith, please proceed to Exam Room 3."
    audio_en = synthesize_tts_chirp3_hd(tts_en, "en-US") or make_test_wav_16khz()
    payload_en = {
        "audio_b64": base64.b64encode(audio_en).decode("ascii"),
        "full_name_readings": ["Alex Smith", "アレックススミス"],
        "surname_readings": ["Smith", "スミス"],
        "kanji_name": "Alex Smith",
        "ticket_number": "305",
        "venue_mode": "hospital",
        "language_mode": "auto",
    }
    rec_req_en = urllib.request.Request(
        f"{base}/recognize",
        data=json.dumps(payload_en).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(rec_req_en, timeout=20) as resp:
        body_en = json.loads(resp.read().decode("utf-8"))
        print("POST /recognize [EN] ->", json.dumps(body_en, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Smoke test Yobareta backend (JA + EN)")
    parser.add_argument("--url", help="Optional base URL of running server (e.g. http://127.0.0.1:8080)")
    parser.add_argument("--id-token", default=None, help="Optional Firebase Auth ID token")
    parser.add_argument("--app-check-token", default=None, help="Optional Firebase App Check token")
    args = parser.parse_args()

    run_local_matcher_checks()
    if args.url:
        run_endpoint_check(args.url, args.id_token, args.app_check_token)
