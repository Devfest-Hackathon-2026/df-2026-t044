"""Cloud Speech-to-Text V2 client using chirp_3 in the 'us' (or 'eu') multi-region.

Key architectural constraints:
- Model: chirp_3, language: ja-JP.
- Endpoint: {STT_LOCATION}-speech.googleapis.com (default: us-speech.googleapis.com)
- Recognizer: projects/{PROJECT_ID}/locations/{STT_LOCATION}/recognizers/_
- ExplicitDecodingConfig: LINEAR16, 16000 Hz, 1 channel (mono).
- Inline phrase set constructed from user's name readings, kanji name, ticket number,
  and waiting-room venue templates.
"""

from __future__ import annotations

import logging
import os
import struct
from typing import List, Optional, Sequence

from google.api_core.client_options import ClientOptions
from google.cloud.speech_v2 import SpeechClient
from google.cloud.speech_v2.types import cloud_speech

logger = logging.getLogger("yobareta.stt")

_VENUE_PHRASE_TEMPLATES = {
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


def strip_wav_header_if_present(audio_bytes: bytes) -> bytes:
    """If `audio_bytes` has a RIFF/WAVE header, returns only the raw PCM payload."""
    if len(audio_bytes) > 44 and audio_bytes[:4] == b"RIFF" and audio_bytes[8:12] == b"WAVE":
        # Walk RIFF chunks to find 'data' chunk accurately
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


def build_inline_phrase_list(
    full_name_readings: Sequence[str],
    surname_readings: Sequence[str],
    kanji_name: Optional[str] = None,
    ticket_number: Optional[str] = None,
    venue_mode: str = "hospital",
) -> List[str]:
    """Builds an inline list of phrases to bias STT toward the user's name & venue terms."""
    phrases: List[str] = []

    def _add(p: str) -> None:
        cleaned = (p or "").strip()
        if cleaned and cleaned not in phrases:
            phrases.append(cleaned)

    if kanji_name:
        _add(kanji_name)
        _add(f"{kanji_name}様")
        _add(f"{kanji_name}さん")

    for r in full_name_readings:
        _add(r)
        _add(f"{r}様")
        _add(f"{r}さん")

    for r in surname_readings:
        _add(r)
        _add(f"{r}様")
        _add(f"{r}さん")

    if ticket_number:
        num = ticket_number.strip()
        if num:
            _add(f"{num}番")
            _add(f"{num}番の方")
            _add(f"{num}番のお客様")
            _add(f"番号札{num}番")

    for tmpl in _VENUE_PHRASE_TEMPLATES.get(venue_mode, _VENUE_PHRASE_TEMPLATES["hospital"]):
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
    ) -> str:
        """Transcribes 16 kHz mono 16-bit LINEAR16 audio using chirp_3.

        Never logs the transcript or audio content.
        """
        pcm_bytes = strip_wav_header_if_present(audio_bytes)
        if not pcm_bytes:
            return ""

        explicit_decoding = cloud_speech.ExplicitDecodingConfig(
            encoding=cloud_speech.ExplicitDecodingConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=16000,
            audio_channel_count=1,
        )

        phrases = build_inline_phrase_list(
            full_name_readings=full_name_readings,
            surname_readings=surname_readings,
            kanji_name=kanji_name,
            ticket_number=ticket_number,
            venue_mode=venue_mode,
        )

        adaptation: Optional[cloud_speech.SpeechAdaptation] = None
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

        config = cloud_speech.RecognitionConfig(
            explicit_decoding_config=explicit_decoding,
            language_codes=["ja-JP"],
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
            # If the model/region does not support inline adaptation parameters,
            # retry immediately without adaptation so recognition never fails.
            if adaptation is not None and ("adaptation" in str(exc).lower() or "phrase" in str(exc).lower() or "invalid" in str(exc).lower()):
                logger.warning("Retrying STT recognize without inline adaptation due to config rejection")
                config_no_adapt = cloud_speech.RecognitionConfig(
                    explicit_decoding_config=explicit_decoding,
                    language_codes=["ja-JP"],
                    model=self.stt_model,
                )
                request_no_adapt = cloud_speech.RecognizeRequest(
                    recognizer=self.recognizer_path,
                    config=config_no_adapt,
                    content=pcm_bytes,
                )
                response = client.recognize(request=request_no_adapt, timeout=6.0)
            else:
                raise

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
