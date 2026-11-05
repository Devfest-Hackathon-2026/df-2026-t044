"""Yobareta Cloud Run Backend (asia-northeast1, stateless, zero-PII logging).

Endpoints:
- GET /warm, POST /warm: Pre-warms SudachiPy dictionary, STT V2 client, Agent Platform Gemini client,
  and Firebase Admin SDK when the user taps Start on their phone.
- POST /recognize: Verifies Firebase Auth ID token + App Check token, transcribes 16 kHz mono LINEAR16
  audio via Speech-to-Text V2 (chirp_3 in 'us', supporting ja-JP and en-US), runs the deterministic
  SudachiPy + English phonetic reading matcher, and verifies candidate matches with Gemini Flash
  on Agent Platform (2.5 s timeout).
- GET /healthz: Liveness check.

Privacy guarantee:
- NEVER logs audio or transcripts (which may contain other patients' names in waiting rooms).
- Logs only latency and scores.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import time
from typing import Dict, List, Optional

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile, status
from pydantic import BaseModel, Field

from auth_verify import ensure_firebase_initialized, verify_request_tokens
from matcher import evaluate_transcript, get_reading_extractor
from stage2_gemini import get_stage2_verifier, merge_stage1_and_stage2
from stt_client import get_stt_recognizer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("yobareta.api")

app = FastAPI(
    title="Yobareta Cloud Run API",
    version="1.1.0",
    docs_url=None,
    redoc_url=None,
)

# Ephemeral in-RAM recall tracker: maps anonymized UID hash -> last 'called' monotonic timestamp.
# Never stores names, transcripts, or audio.
_RECALL_WINDOW_SECONDS = 300.0
_RECENT_CALL_TIMESTAMPS: Dict[str, float] = {}


class RecognizeJsonRequest(BaseModel):
    audio_b64: Optional[str] = Field(
        default=None,
        description="Base64-encoded 16 kHz mono 16-bit PCM (LINEAR16) or WAV audio segment.",
    )
    full_name_readings: List[str] = Field(
        default_factory=list,
        description="Registered katakana or English readings for the user's full name.",
    )
    surname_readings: List[str] = Field(
        default_factory=list,
        description="Registered katakana or English readings for the user's surname only.",
    )
    kanji_name: Optional[str] = Field(
        default=None,
        description="User's display/kanji/English name (e.g. '東海林太郎' or 'Alex Smith').",
    )
    ticket_number: Optional[str] = Field(
        default=None,
        description="Optional waiting/order ticket number (e.g. '305' or 'A-12').",
    )
    venue_mode: str = Field(
        default="hospital",
        description="Venue setting: 'hospital' | 'restaurant' | 'cafe'.",
    )
    language_mode: str = Field(
        default="auto",
        description="Language setting: 'auto' (ja-JP + en-US) | 'ja-JP' | 'en-US'.",
    )
    previously_called: bool = Field(
        default=False,
        description="True if the phone already triggered a 'called' alert recently for this session.",
    )
    full_name_threshold: float = Field(default=0.85, ge=0.5, le=1.0)
    surname_threshold: float = Field(default=0.90, ge=0.5, le=1.0)


class RecognizeResponse(BaseModel):
    tier: str  # "none" | "possible" | "called" | "recall"
    score: float
    matched_trigger: str
    transcript_masked: str
    destination: str
    instruction_plain: str
    latency_ms: int


def _check_and_update_recall_state(uid_hash: str, client_previously_called: bool) -> bool:
    """Checks if this anonymized user was recently called within _RECALL_WINDOW_SECONDS."""
    now = time.monotonic()
    expired = [k for k, ts in _RECENT_CALL_TIMESTAMPS.items() if now - ts > _RECALL_WINDOW_SECONDS]
    for k in expired:
        _RECENT_CALL_TIMESTAMPS.pop(k, None)

    was_called_recently = client_previously_called or (uid_hash in _RECENT_CALL_TIMESTAMPS)
    return was_called_recently


def _record_call_event(uid_hash: str) -> None:
    _RECENT_CALL_TIMESTAMPS[uid_hash] = time.monotonic()


@app.get("/healthz")
async def healthz() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/warm")
@app.post("/warm")
async def warm(
    uid_hash: str = Depends(verify_request_tokens),
) -> Dict[str, object]:
    """Pre-warms SudachiPy dictionary, STT V2 client, Gemini client, and Firebase Admin."""
    t0 = time.perf_counter()
    ensure_firebase_initialized()
    extractor = get_reading_extractor()
    stt = get_stt_recognizer()
    _ = stt.get_client()
    stage2 = get_stage2_verifier()
    _ = stage2.get_client()
    _ = extractor.tokenize_with_readings("診察室へお入りください")
    warm_ms = int((time.perf_counter() - t0) * 1000)
    logger.info(
        json.dumps(
            {
                "event": "warm",
                "sudachi_ready": extractor.is_ready,
                "stt_location": stt.stt_location,
                "warm_ms": warm_ms,
            }
        )
    )
    return {
        "status": "warm",
        "sudachi_ready": extractor.is_ready,
        "stt_location": stt.stt_location,
        "warm_ms": warm_ms,
    }


async def _parse_recognize_payload(
    request: Request,
    audio_file: Optional[UploadFile],
    metadata_json: Optional[str],
) -> tuple[bytes, RecognizeJsonRequest]:
    """Supports both application/json and multipart/form-data uploads."""
    content_type = (request.headers.get("content-type") or "").lower()

    if "multipart/form-data" in content_type:
        if audio_file is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Missing 'audio' file part in multipart/form-data request",
            )
        audio_bytes = await audio_file.read()
        meta_dict = json.loads(metadata_json or "{}")
        req_model = RecognizeJsonRequest(**meta_dict)
        return audio_bytes, req_model

    try:
        body = await request.json()
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid JSON payload",
        ) from exc

    req_model = RecognizeJsonRequest(**body)
    if not req_model.audio_b64:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing 'audio_b64' in JSON request",
        )
    try:
        audio_bytes = base64.b64decode(req_model.audio_b64)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid base64 in 'audio_b64'",
        ) from exc

    return audio_bytes, req_model


@app.post("/recognize", response_model=RecognizeResponse)
async def recognize(
    request: Request,
    audio: Optional[UploadFile] = File(default=None),
    metadata: Optional[str] = Form(default=None),
    uid_hash: str = Depends(verify_request_tokens),
) -> RecognizeResponse:
    """Transcribes audio segment with chirp_3, matches readings (JA+EN), and verifies with Gemini Flash."""
    t_start = time.perf_counter()

    audio_bytes, params = await _parse_recognize_payload(request, audio, metadata)
    if not audio_bytes:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Empty audio payload",
        )

    if len(audio_bytes) > 1_048_576:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="Audio segment exceeds maximum allowed size (1 MB)",
        )

    # 1. Speech-to-Text V2 (chirp_3 in us multi-region, supporting ja-JP and en-US)
    t_stt0 = time.perf_counter()
    stt = get_stt_recognizer()
    try:
        transcript = await asyncio.to_thread(
            stt.recognize_pcm16,
            audio_bytes,
            params.full_name_readings,
            params.surname_readings,
            params.kanji_name,
            params.ticket_number,
            params.venue_mode,
            params.language_mode,
        )
    except Exception as exc:
        logger.error(
            json.dumps(
                {
                    "event": "stt_error",
                    "error_type": type(exc).__name__,
                }
            )
        )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Speech-to-Text recognition failed",
        ) from exc
    stt_ms = int((time.perf_counter() - t_stt0) * 1000)

    # 2. Stage 1: Deterministic SudachiPy (Japanese) + Phonetic (English) matcher
    t_match0 = time.perf_counter()
    was_called_recently = _check_and_update_recall_state(uid_hash, params.previously_called)

    full_thresh = float(os.environ.get("FULL_NAME_THRESHOLD", str(params.full_name_threshold)))
    surname_thresh = float(os.environ.get("SURNAME_THRESHOLD", str(params.surname_threshold)))

    stage1 = evaluate_transcript(
        transcript=transcript,
        full_name_readings=params.full_name_readings,
        surname_readings=params.surname_readings,
        ticket_number=params.ticket_number,
        kanji_name=params.kanji_name,
        previously_called=was_called_recently,
        full_name_threshold=full_thresh,
        surname_threshold=surname_thresh,
    )
    matcher_ms = int((time.perf_counter() - t_match0) * 1000)

    # 3. Stage 2: Gemini Flash on Agent Platform (only on candidate matches, 2.5s timeout)
    stage2_used = False
    stage2_timed_out = False
    stage2_ms = 0
    final_result = stage1

    if stage1.candidate_for_stage2 and transcript:
        stage2_used = True
        t_s2 = time.perf_counter()
        verifier = get_stage2_verifier()
        s2_res = await verifier.verify_candidate(
            transcript=transcript,
            full_name_readings=params.full_name_readings,
            surname_readings=params.surname_readings,
            kanji_name=params.kanji_name,
            ticket_number=params.ticket_number,
            venue_mode=params.venue_mode,
            stage1=stage1,
        )
        stage2_ms = int((time.perf_counter() - t_s2) * 1000)
        stage2_timed_out = s2_res.timed_out
        final_result = merge_stage1_and_stage2(
            stage1=stage1,
            stage2=s2_res,
            previously_called=was_called_recently,
        )

    if final_result.tier in ("called", "recall"):
        _record_call_event(uid_hash)

    total_latency_ms = int((time.perf_counter() - t_start) * 1000)

    # Privacy-first logging: ONLY log latency and scores, NEVER audio or transcripts!
    logger.info(
        json.dumps(
            {
                "event": "recognize",
                "tier": final_result.tier,
                "match_type": final_result.match_type,
                "score": final_result.score,
                "stage2_used": stage2_used,
                "stage2_timed_out": stage2_timed_out,
                "stt_ms": stt_ms,
                "matcher_ms": matcher_ms,
                "stage2_ms": stage2_ms,
                "latency_ms": total_latency_ms,
            }
        )
    )

    return RecognizeResponse(
        tier=final_result.tier,
        score=final_result.score,
        matched_trigger=final_result.matched_trigger,
        transcript_masked=final_result.transcript_masked,
        destination=final_result.destination,
        instruction_plain=final_result.instruction_plain,
        latency_ms=total_latency_ms,
    )
