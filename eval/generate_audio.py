#!/usr/bin/env python3
"""Generates synthetic waiting-room audio clips (.wav, 16kHz mono PCM) for all 26 eval calls.

Uses gTTS (Google Text-to-Speech) and pydub / wave to generate realistic hospital/cafe announcements.
Usage:
    pip install gTTS pydub
    python eval/generate_audio.py
"""

import csv
import io
import os
from pathlib import Path
import shutil
import wave
from gtts import gTTS

try:
    from pydub import AudioSegment
    HAS_PYDUB = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
except ImportError:
    HAS_PYDUB = False

def main():
    eval_dir = Path(__file__).resolve().parent
    audio_dir = eval_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)

    calls_csv = eval_dir / "calls.csv"
    with open(calls_csv, mode="r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        calls = list(reader)

    print(f"Generating synthetic audio clips for {len(calls)} evaluation calls...")

    for row in calls:
        call_id = row["id"]
        lang = row.get("language", "ja")
        announcement = row["announcement"].strip()

        # Handle non-speech or special chime-only instructions
        if announcement.startswith("(") or not announcement:
            if HAS_PYDUB:
                # Generate 2 seconds of silence/simulated chime tone
                silence = AudioSegment.silent(duration=2000)
                out_path = audio_dir / f"{call_id}.wav"
                silence.set_frame_rate(16000).set_channels(1).set_sample_width(2).export(out_path, format="wav")
                print(f"  [{call_id}] Generated 2s silence/chime placeholder -> {out_path.name}")
            continue

        tts_lang = "ja" if lang == "ja" else "en"
        tts = gTTS(text=announcement, lang=tts_lang, slow=False)
        mp3_fp = io.BytesIO()
        tts.write_to_fp(mp3_fp)
        mp3_fp.seek(0)

        if HAS_PYDUB:
            # Convert to standard 16kHz mono 16-bit PCM WAV (same format as Yobareta mic capture)
            sound = AudioSegment.from_file(mp3_fp, format="mp3")
            sound = sound.set_frame_rate(16000).set_channels(1).set_sample_width(2)
            out_path = audio_dir / f"{call_id}.wav"
            sound.export(out_path, format="wav")
            print(f"  [{call_id}] Generated 16kHz WAV ({tts_lang}): '{announcement[:30]}...' -> {out_path.name}")
        else:
            # Fallback to .mp3 if pydub/ffmpeg is not installed
            out_path = audio_dir / f"{call_id}.mp3"
            with open(out_path, "wb") as out_f:
                out_f.write(mp3_fp.read())
            print(f"  [{call_id}] Generated MP3 ({tts_lang}): '{announcement[:30]}...' -> {out_path.name}")

    print("\nDone! Audio files saved in:", audio_dir)

if __name__ == "__main__":
    main()
