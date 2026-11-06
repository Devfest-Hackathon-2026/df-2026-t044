"""Cloud Speech-to-Text V2 client using chirp_3 in the 'us' (or 'eu') multi-region.

Key architectural constraints:
- Model: chirp_3, languages: ja-JP and/or en-US.
- Endpoint: {STT_LOCATION}-speech.googleapis.com (default: us-speech.googleapis.com)
- Recognizer: projects/{PROJECT_ID}/locations/{STT_LOCATION}/recognizers/_
- ExplicitDecodingConfig: LINEAR16, 16000 Hz, 1 channel (mono).
- Inline phrase set constructed from user's name readings, kanji/English name, ticket number,
  and waiting-room venue templates (Japanese + English).
"""

from __future__ import annotations

import logging
import os
import re
import struct
from typing import List, Optional, Sequence

from google.api_core.client_options import ClientOptions
from google.cloud.speech_v2 import SpeechClient
from google.cloud.speech_v2.types import cloud_speech

logger = logging.getLogger("yobareta.stt")

_VENUE_PHRASE_TEMPLATES_JA = {
    "hospital": [
        "診察室へお入りください",
        "番診察室",
        "番窓口",
        "受付へお越しください",
        "会計窓口",
        "中待合",
        "処置室",
        "検査室",
        "再度お呼びいたします",
    ],
    "restaurant": [
        "名様でお待ちの",
        "番でお待ちの",
        "お席のご用意ができました",
        "ご案内いたします",
        "カウンター席",
        "テーブル席",
    ],
    "cafe": [
        "ご注文番号",
        "番号札",
        "番のお客様",
        "お渡し口",
        "受け取りカウンター",
        "お待たせいたしました",
    ],
}

_VENUE_PHRASE_TEMPLATES_EN = {
    "hospital": [
        "Exam Room",
        "Consultation Room",
        "Please proceed to room",
        "Counter",
        "Window",
        "Reception",
        "Pharmacy",
        "Final call for",
    ],
    "restaurant": [
        "Party of",
        "Table for",
        "Your table is ready",
        "Host stand",
        "Now seating",
    ],
    "cafe": [
        "Order number",
        "Ticket number",
        "Pickup counter",
        "Now serving",
        "Ready at the counter",
    ],
}


def strip_wav_header_if_present(audio_bytes: bytes) -> bytes:
    """If `audio_bytes` has a RIFF/WAVE header, returns only the raw PCM payload."""
    if len(audio_bytes) > 44 and audio_bytes[:4] == b"RIFF" and audio_bytes[8:12] == b"WAVE":
        offset = 12
        while offset + 8 <= len(audio_bytes):
            chunk_id = audio_bytes[offset : offset + 4]
            chunk_size = struct.unpack("<I", audio_bytes[offset + 4 : offset + 8])[0]
            offset += 8
            if chunk_id == b"data":
                return audio_bytes[offset : offset + chunk_size]
            offset += chunk_size
        return audio_bytes[44:]
    return audio_bytes


def resolve_language_codes(
    language_mode: str,
    full_name_readings: Sequence[str],
    kanji_name: Optional[str],
) -> List[str]:
    """Resolves STT language_codes list from language_mode ('auto' | 'ja-JP' | 'en-US')."""
    mode = (language_mode or "auto").strip().lower()
    if mode in ("en", "en-us", "english"):
        return ["en-US"]
    if mode in ("ja", "ja-jp", "japanese"):
        return ["ja-JP"]
    # 'auto' / bilingual mode: put primary script language first, secondary second
    has_latin = bool(kanji_name and re.search(r"[A-Za-z]", kanji_name)) or any(
        re.search(r"[A-Za-z]", r) for r in full_name_readings
    )
    if has_latin:
        return ["en-US", "ja-JP"]
    return ["ja-JP", "en-US"]


def build_inline_phrase_list(
    full_name_readings: Sequence[str],
    surname_readings: Sequence[str],
    kanji_name: Optional[str] = None,
    ticket_number: Optional[str] = None,
    venue_mode: str = "hospital",
    language_codes: Sequence[str] = ("ja-JP", "en-US"),
) -> List[str]:
    """Builds an inline list of phrases to bias STT toward the user's name & venue terms."""
    phrases: List[str] = []

    def _add(p: str) -> None:
        cleaned = (p or "").strip()
        if cleaned and cleaned not in phrases:
            phrases.append(cleaned)

    include_ja = any(lc.startswith("ja") for lc in language_codes)
    include_en = any(lc.startswith("en") for lc in language_codes)

    if kanji_name:
        _add(kanji_name)
        if include_ja and not re.search(r"[A-Za-z]", kanji_name):
            _add(f"{kanji_name}様")
            _add(f"{kanji_name}さん")

    for r in full_name_readings:
        _add(r)
        if re.search(r"[A-Za-z]", r):
            _add(f"Mr. {r}")
            _add(f"Ms. {r}")
        elif include_ja:
            _add(f"{r}様")
            _add(f"{r}さん")

    for r in surname_readings:
        _add(r)
        if re.search(r"[A-Za-z]", r):
            _add(f"Mr. {r}")
            _add(f"Ms. {r}")
        elif include_ja:
            _add(f"{r}様")
            _add(f"{r}さん")

    if ticket_number:
        num = ticket_number.strip()
        if num:
            if include_ja:
                _add(f"{num}番")
                _add(f"{num}番の方")
                _add(f"番号札{num}番")
            if include_en:
                _add(f"Order {num}")
                _add(f"Number {num}")
                _add(f"Ticket {num}")

    if include_ja:
        ja_templates = (
            [p for v in _VENUE_PHRASE_TEMPLATES_JA.values() for p in v]
            if venue_mode not in _VENUE_PHRASE_TEMPLATES_JA
            else _VENUE_PHRASE_TEMPLATES_JA[venue_mode]
        )
        for tmpl in ja_templates:
            _add(tmpl)
    if include_en:
        en_templates = (
            [p for v in _VENUE_PHRASE_TEMPLATES_EN.values() for p in v]
            if venue_mode not in _VENUE_PHRASE_TEMPLATES_EN
            else _VENUE_PHRASE_TEMPLATES_EN[venue_mode]
        )
        for tmpl in en_templates:
            _add(tmpl)

    return phrases[:64]


class Chirp3Recognizer:
    """Singleton wrapper around Speech-to-Text V2 SpeechClient for chirp_3."""

    def __init__(
        self,
        project_id: Optional[str] = None,
        stt_location: Optional[str] = None,
        stt_model: Optional[str] = None,
    ) -> None:
        self.project_id = (
            project_id
            or os.environ.get("GOOGLE_CLOUD_PROJECT")
            or os.environ.get("GCP_PROJECT_ID")
            or "yobareta"
        )
        self.stt_location = (stt_location or os.environ.get("STT_LOCATION") or "us").strip()
        self.stt_model = (stt_model or os.environ.get("STT_MODEL") or "chirp_3").strip()
        self._client: Optional[SpeechClient] = None

    @property
    def recognizer_path(self) -> str:
        return f"projects/{self.project_id}/locations/{self.stt_location}/recognizers/_"

    @property
    def api_endpoint(self) -> str:
        if self.stt_location == "global":
            return "speech.googleapis.com"
        return f"{self.stt_location}-speech.googleapis.com"

    def get_client(self) -> SpeechClient:
        if self._client is None:
            opts = ClientOptions(api_endpoint=self.api_endpoint)
            self._client = SpeechClient(client_options=opts)
        return self._client

    def recognize_pcm16(
        self,
        audio_bytes: bytes,
        full_name_readings: Sequence[str] = (),
        surname_readings: Sequence[str] = (),
        kanji_name: Optional[str] = None,
        ticket_number: Optional[str] = None,
        venue_mode: str = "hospital",
        language_mode: str = "auto",
    ) -> str:
        """Transcribes 16 kHz mono 16-bit LINEAR16 audio using chirp_3 (ja-JP / en-US).

        Never logs the transcript or audio content.
        """
        pcm_bytes = strip_wav_header_if_present(audio_bytes)
        if not pcm_bytes:
            return ""
        # Hard billing guard: cap any single STT request at 10 seconds of 16 kHz 16-bit mono PCM (320,000 bytes)
        max_pcm_bytes = 16000 * 2 * 10
        if len(pcm_bytes) > max_pcm_bytes:
            pcm_bytes = pcm_bytes[:max_pcm_bytes]

        explicit_decoding = cloud_speech.ExplicitDecodingConfig(
            encoding=cloud_speech.ExplicitDecodingConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=16000,
            audio_channel_count=1,
        )

        lang_codes = resolve_language_codes(
            language_mode=language_mode,
            full_name_readings=full_name_readings,
            kanji_name=kanji_name,
        )

        # Note: chirp_3 does not support inline SpeechAdaptation (it rejects with 400 InvalidArgument
        # and forces a slow second round-trip). Only attach adaptation for non-chirp models.
        adaptation: Optional[cloud_speech.SpeechAdaptation] = None
        if not self.stt_model.startswith("chirp"):
            phrases = build_inline_phrase_list(
                full_name_readings=full_name_readings,
                surname_readings=surname_readings,
                kanji_name=kanji_name,
                ticket_number=ticket_number,
                venue_mode=venue_mode,
                language_codes=lang_codes,
            )
            if phrases:
                adaptation = cloud_speech.SpeechAdaptation(
                    phrase_sets=[
                        cloud_speech.SpeechAdaptation.AdaptationPhraseSet(
                            inline_phrase_set=cloud_speech.PhraseSet(
                                phrases=[cloud_speech.PhraseSet.Phrase(value=p) for p in phrases]
                            )
                        )
                    ]
                )

        # chirp_3 performs fastest and avoids 400 fallback retries with a single primary language code
        primary_lang_codes = [lang_codes[0]] if self.stt_model.startswith("chirp") else lang_codes

        config = cloud_speech.RecognitionConfig(
            explicit_decoding_config=explicit_decoding,
            language_codes=primary_lang_codes,
            model=self.stt_model,
            adaptation=adaptation,
        )

        request = cloud_speech.RecognizeRequest(
            recognizer=self.recognizer_path,
            config=config,
            content=pcm_bytes,
        )

        client = self.get_client()
        try:
            response = client.recognize(request=request, timeout=6.0)
        except Exception as exc:
            # Only retry if multi-language or inline adaptation config was rejected (InvalidArgument)
            if "InvalidArgument" not in type(exc).__name__ and "400" not in str(exc):
                raise
            logger.warning("Retrying STT recognize with single primary language (%s) and no adaptation", lang_codes[0])
            config_fallback = cloud_speech.RecognitionConfig(
                explicit_decoding_config=explicit_decoding,
                language_codes=[lang_codes[0]],
                model=self.stt_model,
            )
            request_fallback = cloud_speech.RecognizeRequest(
                recognizer=self.recognizer_path,
                config=config_fallback,
                content=pcm_bytes,
            )
            response = client.recognize(request=request_fallback, timeout=6.0)

        segments: List[str] = []
        for result in response.results:
            if result.alternatives:
                segments.append(result.alternatives[0].transcript)

        return " ".join(segments).strip()


_GLOBAL_STT: Optional[Chirp3Recognizer] = None


def get_stt_recognizer() -> Chirp3Recognizer:
    global _GLOBAL_STT
    if _GLOBAL_STT is None:
        _GLOBAL_STT = Chirp3Recognizer()
    return _GLOBAL_STT
