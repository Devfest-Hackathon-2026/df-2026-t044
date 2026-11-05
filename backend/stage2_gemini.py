"""Stage 2 verification and destination extractor using Gemini Flash on Agent Platform.

Key architectural constraints:
- Uses Google GenAI SDK with `vertexai=True` (Agent Platform / Vertex AI via Service Account ADC, NO API keys).
- Invoked ONLY when the Stage 1 deterministic reading matcher flags a candidate match.
- Enforces a strict 2.5 s timeout; on timeout or error, falls back to Stage 1 matcher result.
- Surname-only matches are strictly capped at tier='possible' (never escalated to full 'called').
- Never logs transcripts or user names.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
import logging
import os
from typing import Optional, Sequence

from matcher import MatchResult

try:
    from google import genai
    from google.genai import types as genai_types
    _GENAI_AVAILABLE = True
except ImportError:  # pragma: no cover
    genai = None
    genai_types = None
    _GENAI_AVAILABLE = False

logger = logging.getLogger("yobareta.stage2")


@dataclass
class Stage2Result:
    addressed_to_user: bool
    confidence: float
    call_type: str  # "new_call" | "recall" | "other_person" | "not_a_call"
    destination: str
    instruction_plain: str
    timed_out: bool = False
    used_fallback: bool = False


_SYSTEM_INSTRUCTION = """You are Stage 2 of an assistive waiting-room call detector for Deaf and hard-of-hearing users in Japan.
Given an announcement transcript from a hospital, restaurant, or cafe waiting room, and the user's registered name readings / ticket number:
1. Determine whether this announcement is calling the user (`addressed_to_user`: true/false).
   - If the announcement is calling a DIFFERENT given name with the same surname (e.g. user is 佐藤太郎 [サトウタロウ], announcement calls 佐藤花子様), set `addressed_to_user` to false and `call_type` to "other_person".
   - If the announcement is background chatter and not a call-out announcement, set `addressed_to_user` to false and `call_type` to "not_a_call".
2. Classify `call_type` as one of: "new_call", "recall" (if repeating/re-calling an unanswered call), "other_person", or "not_a_call".
3. Extract `destination` concisely in English + Japanese (e.g. "Exam Room 3 (3番診察室)", "Counter 2 (2番窓口)", "Seating Host (お席のご案内)", "Pickup Counter (お渡し口)").
4. Write `instruction_plain` as a short, glanceable smartwatch/phone banner in English (with key room/counter number), e.g.:
   - "You were called → Exam Room 3"
   - "RE-CALL: You were called → Exam Room 3"
   - "Your table is ready → Please see the host"
   - "Order #305 ready → Pickup Counter"
Respond strictly with JSON matching the schema."""


class Stage2GeminiVerifier:
    """Singleton wrapper around Agent Platform (Vertex AI) Gemini Flash."""

    def __init__(
        self,
        project_id: Optional[str] = None,
        location: Optional[str] = None,
        model_name: Optional[str] = None,
        timeout_seconds: float = 2.5,
    ) -> None:
        self.project_id = (
            project_id
            or os.environ.get("GOOGLE_CLOUD_PROJECT")
            or os.environ.get("GCP_PROJECT_ID")
            or "yobareta"
        )
        self.location = (location or os.environ.get("GEMINI_LOCATION") or "asia-northeast1").strip()
        self.model_name = (model_name or os.environ.get("GEMINI_MODEL") or "gemini-2.5-flash").strip()
        self.timeout_seconds = float(os.environ.get("STAGE2_TIMEOUT_SECONDS", str(timeout_seconds)))
        self._client = None

    def get_client(self):
        if not _GENAI_AVAILABLE:
            return None
        if self._client is None:
            self._client = genai.Client(
                vertexai=True,
                project=self.project_id,
                location=self.location,
            )
        return self._client

    def _call_gemini_sync(
        self,
        transcript: str,
        full_name_readings: Sequence[str],
        surname_readings: Sequence[str],
        kanji_name: Optional[str],
        ticket_number: Optional[str],
        venue_mode: str,
        stage1: MatchResult,
    ) -> Stage2Result:
        client = self.get_client()
        if client is None:
            return Stage2Result(
                addressed_to_user=(stage1.tier != "none"),
                confidence=stage1.score,
                call_type="recall" if stage1.tier == "recall" else "new_call",
                destination=stage1.destination,
                instruction_plain=stage1.instruction_plain,
                used_fallback=True,
            )

        user_prompt = json.dumps(
            {
                "venue_mode": venue_mode,
                "user_profile": {
                    "kanji_name": kanji_name or "",
                    "full_name_readings": list(full_name_readings),
                    "surname_readings": list(surname_readings),
                    "ticket_number": ticket_number or "",
                },
                "stage1_matcher": {
                    "match_type": stage1.match_type,
                    "score": stage1.score,
                    "matched_trigger": stage1.matched_trigger,
                },
                "announcement_transcript": transcript,
            },
            ensure_ascii=False,
        )

        response_schema = {
            "type": "OBJECT",
            "properties": {
                "addressed_to_user": {"type": "BOOLEAN"},
                "confidence": {"type": "NUMBER"},
                "call_type": {
                    "type": "STRING",
                    "enum": ["new_call", "recall", "other_person", "not_a_call"],
                },
                "destination": {"type": "STRING"},
                "instruction_plain": {"type": "STRING"},
            },
            "required": [
                "addressed_to_user",
                "confidence",
                "call_type",
                "destination",
                "instruction_plain",
            ],
        }

        config = genai_types.GenerateContentConfig(
            system_instruction=_SYSTEM_INSTRUCTION,
            temperature=0.0,
            max_output_tokens=200,
            response_mime_type="application/json",
            response_schema=response_schema,
        )

        response = client.models.generate_content(
            model=self.model_name,
            contents=user_prompt,
            config=config,
        )
        parsed = json.loads(response.text or "{}")
        return Stage2Result(
            addressed_to_user=bool(parsed.get("addressed_to_user", False)),
            confidence=float(parsed.get("confidence", stage1.score)),
            call_type=str(parsed.get("call_type", "new_call")),
            destination=str(parsed.get("destination") or stage1.destination or ""),
            instruction_plain=str(parsed.get("instruction_plain") or stage1.instruction_plain or ""),
            timed_out=False,
            used_fallback=False,
        )

    async def verify_candidate(
        self,
        transcript: str,
        full_name_readings: Sequence[str],
        surname_readings: Sequence[str],
        kanji_name: Optional[str],
        ticket_number: Optional[str],
        venue_mode: str,
        stage1: MatchResult,
    ) -> Stage2Result:
        """Runs Stage 2 Gemini Flash with a hard 2.5s timeout and automatic fallback."""
        try:
            return await asyncio.wait_for(
                asyncio.to_thread(
                    self._call_gemini_sync,
                    transcript,
                    full_name_readings,
                    surname_readings,
                    kanji_name,
                    ticket_number,
                    venue_mode,
                    stage1,
                ),
                timeout=self.timeout_seconds,
            )
        except asyncio.TimeoutError:
            logger.warning("Stage 2 Gemini timed out after %.2fs; falling back to Stage 1 matcher", self.timeout_seconds)
            return Stage2Result(
                addressed_to_user=(stage1.tier != "none"),
                confidence=stage1.score,
                call_type="recall" if stage1.tier == "recall" else "new_call",
                destination=stage1.destination,
                instruction_plain=stage1.instruction_plain,
                timed_out=True,
                used_fallback=True,
            )
        except Exception as exc:
            logger.warning("Stage 2 Gemini failed (%s); falling back to Stage 1 matcher", type(exc).__name__)
            return Stage2Result(
                addressed_to_user=(stage1.tier != "none"),
                confidence=stage1.score,
                call_type="recall" if stage1.tier == "recall" else "new_call",
                destination=stage1.destination,
                instruction_plain=stage1.instruction_plain,
                timed_out=False,
                used_fallback=True,
            )


def merge_stage1_and_stage2(
    stage1: MatchResult,
    stage2: Stage2Result,
    previously_called: bool = False,
) -> MatchResult:
    """Combines Stage 1 deterministic matcher output with Stage 2 Gemini output.

    Enforces key safety invariants:
    1. If Stage 2 fell back or timed out, return Stage 1 unchanged.
    2. Surname-only matches NEVER escalate above 'possible' (even if Gemini confidence is high).
    3. If Stage 2 determines `call_type in ('other_person', 'not_a_call')` and `addressed_to_user` is False,
       downgrade tier to 'none' (unless Stage 1 had an exact 1.0 full-name or ticket match with honorific).
    """
    if stage2.used_fallback:
        return stage1

    # If Gemini explicitly detected that another person with the same surname was called
    # (e.g. "佐藤花子様" when the user is "佐藤太郎"), suppress false alarm!
    if not stage2.addressed_to_user or stage2.call_type in ("other_person", "not_a_call"):
        if stage1.match_type == "surname" or stage1.score < 0.95:
            return MatchResult(
                tier="none",
                score=round(min(stage1.score, 0.5), 4),
                match_type=stage1.match_type,
                matched_trigger="",
                matched_surface="",
                has_honorific=stage1.has_honorific,
                is_recall_phrase=stage1.is_recall_phrase,
                destination="",
                instruction_plain="",
                transcript_masked=stage1.transcript_masked,
                candidate_for_stage2=True,
                debug_details={**stage1.debug_details, "stage2_rejected": stage2.call_type},
            )

    destination = stage2.destination or stage1.destination
    instruction_plain = stage2.instruction_plain or stage1.instruction_plain

    # Invariant: Surname-only NEVER fires a full alert!
    if stage1.match_type == "surname":
        if destination and "Possible" not in instruction_plain:
            instruction_plain = f"Possible call (surname match) → {destination}"
        elif not instruction_plain:
            instruction_plain = "Possible call (surname matched)"
        return MatchResult(
            tier="possible",
            score=round(max(stage1.score, stage2.confidence * 0.9), 4),
            match_type="surname",
            matched_trigger=stage1.matched_trigger,
            matched_surface=stage1.matched_surface,
            has_honorific=stage1.has_honorific,
            is_recall_phrase=stage1.is_recall_phrase or (stage2.call_type == "recall"),
            destination=destination,
            instruction_plain=instruction_plain,
            transcript_masked=stage1.transcript_masked,
            candidate_for_stage2=True,
        )

    # Full-name or ticket-number match confirmed by Stage 2
    is_recall = (
        previously_called
        or stage1.is_recall_phrase
        or stage1.tier == "recall"
        or stage2.call_type == "recall"
    )
    final_tier = "recall" if is_recall else "called"
    if final_tier == "recall" and instruction_plain and not instruction_plain.upper().startswith("RE-CALL"):
        instruction_plain = f"RE-CALL: {instruction_plain}"

    return MatchResult(
        tier=final_tier,
        score=round(max(stage1.score, stage2.confidence), 4),
        match_type=stage1.match_type,
        matched_trigger=stage1.matched_trigger,
        matched_surface=stage1.matched_surface,
        has_honorific=stage1.has_honorific,
        is_recall_phrase=is_recall,
        destination=destination,
        instruction_plain=instruction_plain or "You were called",
        transcript_masked=stage1.transcript_masked,
        candidate_for_stage2=True,
    )


_GLOBAL_STAGE2: Optional[Stage2GeminiVerifier] = None


def get_stage2_verifier() -> Stage2GeminiVerifier:
    global _GLOBAL_STAGE2
    if _GLOBAL_STAGE2 is None:
        _GLOBAL_STAGE2 = Stage2GeminiVerifier()
    return _GLOBAL_STAGE2
