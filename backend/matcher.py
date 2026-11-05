"""Deterministic Japanese reading matcher using SudachiPy (Apache-2.0).

Responsibilities:
1. Tokenize Japanese STT transcripts into katakana readings via SudachiPy.
2. Normalize katakana readings for long-vowel equivalence (e.g., ショウジ == ショージ,
   サトウ == サトー, オオノ == オーノ) and strip honorifics (様/さん/殿/ちゃん/君).
3. Segment katakana strings into phonological morae and compute sliding-window
   mora edit distance against the user's registered full-name and surname readings.
4. Perform exact number matching for ticket/order numbers while distinguishing
   destination room/counter numbers (e.g., "3番診察室") from caller numbers ("305番の方").
5. Extract deterministic fallback destination & plain instructions, and mask transcripts
   so other patients' names are never exposed or logged.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Dict, List, Optional, Sequence, Tuple

try:
    from sudachipy import dictionary as sudachi_dictionary
    from sudachipy import tokenizer as sudachi_tokenizer
    _SUDACHI_AVAILABLE = True
except ImportError:  # pragma: no cover - fallback for lightweight unit test envs
    sudachi_dictionary = None
    sudachi_tokenizer = None
    _SUDACHI_AVAILABLE = False


# Small kana that attach to the preceding kana to form a single mora (拗音 / 外来音).
_SMALL_COMBINING_KANA = set("ァィゥェォャュョヮヵヶぁぃぅぇぉゃゅょゎ")

# Vowel row lookup for each katakana character to canonicalize長音 (long vowels).
_KATAKANA_VOWEL: Dict[str, str] = {}
for _ch in "アカガサザタダナハバパマヤラワァャヮ":
    _KATAKANA_VOWEL[_ch] = "ア"
for _ch in "イキギシジチヂニヒビピミリィ":
    _KATAKANA_VOWEL[_ch] = "イ"
for _ch in "ウヴクグスズツヅヌフブプムユルゥュ":
    _KATAKANA_VOWEL[_ch] = "ウ"
for _ch in "エケゲセゼテデネヘベペメレェヶ":
    _KATAKANA_VOWEL[_ch] = "エ"
for _ch in "オコゴソゾトドノホボポモヨロヲォョ":
    _KATAKANA_VOWEL[_ch] = "オ"

# Honorific suffixes in kanji/kana and katakana readings
_HONORIFIC_SURFACES = ("名様", "様", "さん", "さま", "殿", "ちゃん", "君", "くん", "氏")
_HONORIFIC_READINGS = ("メイサマ", "サマ", "サン", "ドノ", "チャン", "クン", "シ")

# Re-call indicator phrases in waiting rooms
_RECALL_KEYWORDS = (
    "再度",
    "もう一度",
    "お見えになりません",
    "いらっしゃいますか",
    "いらっしゃいませんか",
    "お呼びしております",
    "お呼びしています",
    "繰り返します",
)

# Destination patterns for deterministic extraction (Stage 1 fallback when Gemini times out)
_DESTINATION_PATTERNS: List[Tuple[re.Pattern[str], str, str]] = [
    (
        re.compile(r"(\d+|[一二三四五六七八九十]+)\s*番\s*(診察室|診|内診室|処置室|検査室|レントゲン室|CT室|MRI室)"),
        "Exam Room {num} ({raw})",
        "You were called → Exam Room {num}",
    ),
    (
        re.compile(r"(\d+|[一二三四五六七八九十]+)\s*番\s*(窓口|カウンター|受付|会計|お会計)"),
        "Counter {num} ({raw})",
        "You were called → Counter {num}",
    ),
    (
        re.compile(r"(受付|会計|お会計|薬局|お薬窓口|処方箋窓口|待合室|中待合)"),
        "{raw}",
        "You were called → Proceed to {raw}",
    ),
    (
        re.compile(r"(お席|テーブル|カウンター席|ご案内)"),
        "Seating / Host Stand ({raw})",
        "Your table is ready → Please see the host",
    ),
    (
        re.compile(r"(受け取りカウンター|お渡し口|ピックアップ|カウンター)"),
        "Pickup Counter ({raw})",
        "Your order is ready → Pickup Counter",
    ),
]

# Contexts where a number refers to a destination room/counter or party size, NOT a ticket number
_NON_TICKET_NUMBER_SUFFIXES = re.compile(
    r"^(\d+)\s*(?:番\s*(?:診察室|診|窓口|カウンター|受付|会計|テーブル|席|ゲート|ホーム|処置室|検査室)|名様|名|人|階|時|分|秒|歳|才)"
)

_KANJI_DIGITS = {
    "〇": 0, "零": 0, "ゼロ": 0,
    "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9,
}
_FULLWIDTH_DIGIT_TRANS = str.maketrans("０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ",
                                       "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")


@dataclass
class MatchResult:
    tier: str  # "none" | "possible" | "called" | "recall"
    score: float
    match_type: str  # "none" | "surname" | "full_name" | "ticket_number"
    matched_trigger: str
    matched_surface: str
    has_honorific: bool
    is_recall_phrase: bool
    destination: str
    instruction_plain: str
    transcript_masked: str
    candidate_for_stage2: bool
    debug_details: Dict[str, object] = field(default_factory=dict)


class SudachiReadingExtractor:
    """Wraps SudachiPy tokenizer (singleton) to convert Japanese text into katakana readings."""

    def __init__(self) -> None:
        self._tokenizer = None
        if _SUDACHI_AVAILABLE:
            try:
                self._tokenizer = sudachi_dictionary.Dictionary(dict="core").create()
            except Exception:
                self._tokenizer = None

    @property
    def is_ready(self) -> bool:
        return self._tokenizer is not None

    def tokenize_with_readings(self, text: str) -> List[Tuple[str, str]]:
        """Returns list of (surface, katakana_reading) tokens."""
        if not text:
            return []
        if self._tokenizer is not None:
            mode = sudachi_tokenizer.Tokenizer.SplitMode.C
            morphemes = self._tokenizer.tokenize(text, mode)
            result: List[Tuple[str, str]] = []
            for m in morphemes:
                surface = m.surface()
                reading = m.reading_form()
                if not reading or reading == "*":
                    reading = hiragana_to_katakana(surface)
                else:
                    reading = hiragana_to_katakana(reading)
                result.append((surface, reading))
            return result
        # Fallback if SudachiPy is not installed in a minimal test environment:
        return [(text, hiragana_to_katakana(text))]


_GLOBAL_EXTRACTOR: Optional[SudachiReadingExtractor] = None


def get_reading_extractor() -> SudachiReadingExtractor:
    global _GLOBAL_EXTRACTOR
    if _GLOBAL_EXTRACTOR is None:
        _GLOBAL_EXTRACTOR = SudachiReadingExtractor()
    return _GLOBAL_EXTRACTOR


def hiragana_to_katakana(text: str) -> str:
    """Converts hiragana characters (U+3041..U+3096) to katakana (U+30A1..U+30F6)."""
    chars: List[str] = []
    for ch in text:
        code = ord(ch)
        if 0x3041 <= code <= 0x3096:
            chars.append(chr(code + 0x60))
        else:
            chars.append(ch)
    return "".join(chars)


def split_into_morae(katakana: str) -> List[str]:
    """Splits a katakana string into phonological morae (e.g. 'ショウジ' -> ['ショ', 'ウ', 'ジ'])."""
    cleaned = re.sub(r"[^ァ-ヴー]", "", hiragana_to_katakana(katakana))
    morae: List[str] = []
    for ch in cleaned:
        if ch in _SMALL_COMBINING_KANA and morae:
            morae[-1] = morae[-1] + ch
        else:
            morae.append(ch)
    return morae


def normalize_reading_morae(katakana: str, strip_honorific: bool = True) -> List[str]:
    """Normalizes katakana into canonical morae with long-vowel equivalence.

    Rules:
    - Converts hiragana to katakana and strips spaces/punctuation.
    - Optionally strips trailing honorifics (サマ, サン, ドノ, チャン, クン).
    - Canonicalizes long vowels so that:
        * ओ/उ column + 'ウ' or 'オ' -> 'ー' (e.g. ショウジ -> ショージ, サトウ -> サトー, オオノ -> オーノ)
        * ए/इ column + 'イ' or 'エ' -> 'ー' (e.g. ケイコ -> ケーコ)
        * अ column + 'ア' -> 'ー'
        * ウ column + 'ウ' -> 'ー' (e.g. ユウキ -> ユーキ)
        * イ column + 'イ' -> 'ー' (e.g. ニイガタ -> ニーガタ)
    """
    kana = re.sub(r"\s+", "", hiragana_to_katakana(katakana))
    if strip_honorific:
        for hon in _HONORIFIC_READINGS:
            if kana.endswith(hon) and len(kana) > len(hon):
                kana = kana[: -len(hon)]
                break

    raw_morae = split_into_morae(kana)
    canonical: List[str] = []
    for mora in raw_morae:
        if canonical:
            prev_last_char = canonical[-1][-1]
            prev_vowel = _KATAKANA_VOWEL.get(prev_last_char)
            if mora in ("ウ", "オ") and prev_vowel == "オ":
                canonical.append("ー")
                continue
            if mora in ("イ", "エ") and prev_vowel == "エ":
                canonical.append("ー")
                continue
            if mora == "ア" and prev_vowel == "ア":
                canonical.append("ー")
                continue
            if mora == "ウ" and prev_vowel == "ウ":
                canonical.append("ー")
                continue
            if mora == "イ" and prev_vowel == "イ":
                canonical.append("ー")
                continue
        canonical.append(mora)
    return canonical


def mora_edit_distance(seq_a: Sequence[str], seq_b: Sequence[str]) -> int:
    """Standard Levenshtein distance at the mora level."""
    n, m = len(seq_a), len(seq_b)
    if n == 0:
        return m
    if m == 0:
        return n
    dp = list(range(m + 1))
    for i in range(1, n + 1):
        prev = dp[0]
        dp[0] = i
        for j in range(1, m + 1):
            temp = dp[j]
            cost = 0 if seq_a[i - 1] == seq_b[j - 1] else 1
            dp[j] = min(
                dp[j] + 1,      # deletion
                dp[j - 1] + 1,  # insertion
                prev + cost,    # substitution
            )
            prev = temp
    return dp[m]


def mora_similarity(seq_a: Sequence[str], seq_b: Sequence[str]) -> float:
    """Normalized mora similarity in [0.0, 1.0]: 1.0 - (edit_distance / max_len)."""
    if not seq_a or not seq_b:
        return 0.0
    max_len = max(len(seq_a), len(seq_b))
    dist = mora_edit_distance(seq_a, seq_b)
    return max(0.0, 1.0 - (dist / max_len))


def best_sliding_mora_match(
    transcript_tokens: Sequence[Tuple[str, str]],
    target_reading: str,
) -> Tuple[float, str, bool]:
    """Finds the highest-scoring mora window in `transcript_tokens` for `target_reading`.

    Returns:
        (best_score, matched_surface, followed_by_honorific)
    """
    target_morae = normalize_reading_morae(target_reading, strip_honorific=True)
    if not target_morae:
        return 0.0, "", False

    # Build a flat list of morae from the transcript along with token/character provenance
    flat_morae: List[str] = []
    mora_to_token_idx: List[int] = []

    full_reading = "".join(r for _, r in transcript_tokens)
    if not full_reading:
        return 0.0, "", False

    # Also build per-token mora spans so we can reconstruct the matched surface text
    for idx, (_, reading) in enumerate(transcript_tokens):
        token_morae = split_into_morae(reading)
        for mora in token_morae:
            flat_morae.append(mora)
            mora_to_token_idx.append(idx)

    # Canonicalize long vowels across the full transcript mora stream while keeping index alignment
    canon_morae: List[str] = []
    for mora in flat_morae:
        if canon_morae:
            prev_last_char = canon_morae[-1][-1]
            prev_vowel = _KATAKANA_VOWEL.get(prev_last_char)
            if (
                (mora in ("ウ", "オ") and prev_vowel == "オ")
                or (mora in ("イ", "エ") and prev_vowel == "エ")
                or (mora == "ア" and prev_vowel == "ア")
                or (mora == "ウ" and prev_vowel == "ウ")
                or (mora == "イ" and prev_vowel == "イ")
            ):
                canon_morae.append("ー")
                continue
        canon_morae.append(mora)

    if not canon_morae:
        return 0.0, "", False

    target_len = len(target_morae)
    min_win = max(1, target_len - 2)
    max_win = target_len + 2

    best_score = 0.0
    best_start = 0
    best_end = 0

    n = len(canon_morae)
    for start in range(n):
        for win_len in range(min_win, max_win + 1):
            end = start + win_len
            if end > n:
                break
            window = canon_morae[start:end]
            score = mora_similarity(window, target_morae)
            if score > best_score:
                best_score = score
                best_start = start
                best_end = end

    if best_score == 0.0:
        return 0.0, "", False

    start_tok = mora_to_token_idx[best_start]
    end_tok = mora_to_token_idx[min(best_end - 1, len(mora_to_token_idx) - 1)]
    matched_surface = "".join(transcript_tokens[i][0] for i in range(start_tok, end_tok + 1))

    # Check if the next morae or next token is an honorific (様 / さん / etc.)
    rem_morae_str = "".join(flat_morae[best_end : best_end + 4])
    followed_by_honorific = any(rem_morae_str.startswith(h) for h in _HONORIFIC_READINGS)
    if not followed_by_honorific and end_tok + 1 < len(transcript_tokens):
        next_surface = transcript_tokens[end_tok + 1][0]
        followed_by_honorific = next_surface.startswith(_HONORIFIC_SURFACES)

    return best_score, matched_surface, followed_by_honorific


def _kanji_number_to_int(text: str) -> Optional[int]:
    """Converts simple Japanese kanji numerals (up to 9999) into an integer."""
    if not text:
        return None
    if text.isdigit():
        return int(text)

    # Positional kanji numeral parsing (千, 百, 十) vs digit-by-digit (e.g. 三〇五 -> 305)
    if all(ch in _KANJI_DIGITS for ch in text):
        return int("".join(str(_KANJI_DIGITS[ch]) for ch in text))

    multipliers = {"千": 1000, "百": 100, "十": 10}
    total = 0
    current = 0
    for ch in text:
        if ch in _KANJI_DIGITS:
            current = _KANJI_DIGITS[ch]
        elif ch in multipliers:
            mult = multipliers[ch]
            total += (current if current > 0 else 1) * mult
            current = 0
        else:
            return None
    total += current
    return total if total > 0 else None


def normalize_ticket_number(raw: Optional[str]) -> str:
    """Normalizes a user-supplied ticket/order number (e.g. 'Ａ-３０５番' -> 'A305' or '305')."""
    if not raw:
        return ""
    norm = raw.translate(_FULLWIDTH_DIGIT_TRANS).strip().upper()
    norm = re.sub(r"(番号札|番号|ご注文番号|受付番号|番|様|さん|\s|-|ー)", "", norm)
    return norm


def match_ticket_number_in_transcript(transcript: str, ticket_number: Optional[str]) -> Tuple[bool, str]:
    """Checks for an exact ticket/order number match in the transcript.

    Carefully avoids matching room/counter numbers (e.g., '3番診察室', '2番窓口', '2名様')
    when those numbers are part of a destination or party size rather than the caller's number.
    """
    norm_target = normalize_ticket_number(ticket_number)
    if not norm_target:
        return False, ""

    norm_tx = transcript.translate(_FULLWIDTH_DIGIT_TRANS).upper()

    # Check alphanumeric ticket numbers like "A12" or "B-05"
    alpha_num_match = re.match(r"^([A-Z]+)(\d+)$", norm_target)
    if alpha_num_match:
        prefix, digits = alpha_num_match.groups()
        pattern = re.compile(rf"{re.escape(prefix)}\s*[-ーの]?\s*0*{re.escape(digits.lstrip('0') or '0')}(?!\d)")
        m = pattern.search(norm_tx)
        if m:
            return True, m.group(0)
        return False, ""

    # Pure numeric ticket/order number
    if not norm_target.isdigit():
        return (norm_target in norm_tx), (norm_target if norm_target in norm_tx else "")

    target_int = int(norm_target)

    # 1. Check Arabic numerals in the normalized transcript
    for m in re.finditer(r"\d+", norm_tx):
        val = int(m.group(0))
        if val == target_int:
            tail = norm_tx[m.start() :]
            # Reject if this number is clearly a destination room/counter or party size (e.g. "3番診察室", "2名様")
            if _NON_TICKET_NUMBER_SUFFIXES.match(tail):
                continue
            return True, m.group(0)

    # 2. Check Kanji numerals (e.g. "三百五番", "三〇五番", "十二番")
    for m in re.finditer(r"[〇零一二三四五六七八九十百千]+", norm_tx):
        val = _kanji_number_to_int(m.group(0))
        if val == target_int:
            tail_after = norm_tx[m.end() :]
            if re.match(r"^\s*(?:番\s*(?:診察室|診|窓口|カウンター|受付|会計|テーブル|席|処置室|検査室)|名様|名|人|階|時|分)", tail_after):
                continue
            return True, m.group(0)

    return False, ""


def extract_destination_deterministic(transcript: str) -> Tuple[str, str]:
    """Deterministic regex fallback to extract destination and plain instruction."""
    if not transcript:
        return "", "You were called"

    norm_tx = transcript.translate(_FULLWIDTH_DIGIT_TRANS)
    for pattern, dest_tmpl, instr_tmpl in _DESTINATION_PATTERNS:
        m = pattern.search(norm_tx)
        if m:
            raw = m.group(0).strip()
            if m.lastindex and m.lastindex >= 2:
                num_raw = m.group(1)
                num_val = _kanji_number_to_int(num_raw)
                num_str = str(num_val) if num_val is not None else num_raw
                return dest_tmpl.format(num=num_str, raw=raw), instr_tmpl.format(num=num_str, raw=raw)
            return dest_tmpl.format(raw=raw), instr_tmpl.format(raw=raw)

    return "", "You were called"


def mask_transcript(transcript: str, matched_surface: str, kanji_name: Optional[str] = None) -> str:
    """Masks any person names in the transcript to protect bystander privacy."""
    if not transcript:
        return ""
    masked = transcript
    # Replace anything before 様/さん that isn't the user's own matched surface
    def _replace_bystander(match: re.Match[str]) -> str:
        prefix = match.group(1)
        honorific = match.group(2)
        if matched_surface and matched_surface in prefix:
            return f"{prefix}{honorific}"
        if kanji_name and kanji_name in prefix:
            return f"{prefix}{honorific}"
        return f"[***]{honorific}"

    masked = re.sub(r"([一-龯々ヶァ-ヴーぁ-ん]{1,8})(様|さん|さま)", _replace_bystander, masked)
    return masked


def evaluate_transcript(
    transcript: str,
    full_name_readings: Sequence[str],
    surname_readings: Sequence[str],
    ticket_number: Optional[str] = None,
    kanji_name: Optional[str] = None,
    previously_called: bool = False,
    full_name_threshold: float = 0.85,
    surname_threshold: float = 0.90,
    stage2_candidate_floor: float = 0.72,
) -> MatchResult:
    """Runs the Stage 1 deterministic matcher on an STT transcript."""
    cleaned_tx = (transcript or "").strip()
    if not cleaned_tx:
        return MatchResult(
            tier="none",
            score=0.0,
            match_type="none",
            matched_trigger="",
            matched_surface="",
            has_honorific=False,
            is_recall_phrase=False,
            destination="",
            instruction_plain="",
            transcript_masked="",
            candidate_for_stage2=False,
        )

    extractor = get_reading_extractor()
    tokens = extractor.tokenize_with_readings(cleaned_tx)
    is_recall_phrase = any(kw in cleaned_tx for kw in _RECALL_KEYWORDS)
    destination, instruction_plain = extract_destination_deterministic(cleaned_tx)

    # 1. Exact ticket/order number check
    ticket_matched, ticket_surface = match_ticket_number_in_transcript(cleaned_tx, ticket_number)
    if ticket_matched:
        tier = "recall" if (previously_called or is_recall_phrase) else "called"
        trigger_label = f"#{normalize_ticket_number(ticket_number)}"
        if tier == "recall" and instruction_plain.startswith("You were called"):
            instruction_plain = instruction_plain.replace("You were called", "RE-CALL: You were called again", 1)
        return MatchResult(
            tier=tier,
            score=1.0,
            match_type="ticket_number",
            matched_trigger=trigger_label,
            matched_surface=ticket_surface,
            has_honorific=True,
            is_recall_phrase=is_recall_phrase,
            destination=destination,
            instruction_plain=instruction_plain,
            transcript_masked=mask_transcript(cleaned_tx, ticket_surface, kanji_name),
            candidate_for_stage2=True,
        )

    # Also check if the exact kanji name appears verbatim in the transcript
    kanji_clean = re.sub(r"\s+", "", kanji_name or "")
    tx_no_space = re.sub(r"\s+", "", cleaned_tx)

    # 2. Full name reading evaluation
    best_full_score = 0.0
    best_full_reading = ""
    best_full_surface = ""
    best_full_honorific = False

    for reading in full_name_readings:
        if not reading or not reading.strip():
            continue
        score, surface, has_hon = best_sliding_mora_match(tokens, reading)
        if kanji_clean and len(kanji_clean) >= 2 and kanji_clean in tx_no_space:
            score = max(score, 1.0)
            surface = surface or kanji_clean
        if score > best_full_score:
            best_full_score = score
            best_full_reading = reading.strip()
            best_full_surface = surface
            best_full_honorific = has_hon

    # 3. Surname-only reading evaluation
    best_surname_score = 0.0
    best_surname_reading = ""
    best_surname_surface = ""
    best_surname_honorific = False

    for reading in surname_readings:
        if not reading or not reading.strip():
            continue
        score, surface, has_hon = best_sliding_mora_match(tokens, reading)
        if score > best_surname_score:
            best_surname_score = score
            best_surname_reading = reading.strip()
            best_surname_surface = surface
            best_surname_honorific = has_hon

    # Decide tier based on thresholds:
    # - Full name >= 0.85 -> Called (or Recall if previously_called or is_recall_phrase)
    # - Surname-only >= 0.90 -> Possible (Surname-only NEVER fires a full alert!)
    if best_full_score >= full_name_threshold:
        tier = "recall" if (previously_called or is_recall_phrase) else "called"
        if tier == "recall" and instruction_plain.startswith("You were called"):
            instruction_plain = instruction_plain.replace("You were called", "RE-CALL: You were called again", 1)
        return MatchResult(
            tier=tier,
            score=round(best_full_score, 4),
            match_type="full_name",
            matched_trigger=best_full_reading,
            matched_surface=best_full_surface,
            has_honorific=best_full_honorific,
            is_recall_phrase=is_recall_phrase,
            destination=destination,
            instruction_plain=instruction_plain,
            transcript_masked=mask_transcript(cleaned_tx, best_full_surface, kanji_name),
            candidate_for_stage2=True,
            debug_details={
                "full_score": round(best_full_score, 4),
                "surname_score": round(best_surname_score, 4),
            },
        )

    if best_surname_score >= surname_threshold:
        # Surname-only NEVER fires a full 'called' alert (common surnames like 佐藤/鈴木 cause false alarms)
        possible_instr = (
            f"Possible call (surname match) → {destination}"
            if destination
            else "Possible call (surname matched)"
        )
        return MatchResult(
            tier="possible",
            score=round(best_surname_score, 4),
            match_type="surname",
            matched_trigger=best_surname_reading,
            matched_surface=best_surname_surface,
            has_honorific=best_surname_honorific,
            is_recall_phrase=is_recall_phrase,
            destination=destination,
            instruction_plain=possible_instr,
            transcript_masked=mask_transcript(cleaned_tx, best_surname_surface, kanji_name),
            candidate_for_stage2=True,
            debug_details={
                "full_score": round(best_full_score, 4),
                "surname_score": round(best_surname_score, 4),
            },
        )

    top_score = max(best_full_score, best_surname_score)
    top_type = "full_name" if best_full_score >= best_surname_score else "surname"
    top_trigger = best_full_reading if top_type == "full_name" else best_surname_reading
    top_surface = best_full_surface if top_type == "full_name" else best_surname_surface

    return MatchResult(
        tier="none",
        score=round(top_score, 4),
        match_type="none",
        matched_trigger=top_trigger if top_score >= stage2_candidate_floor else "",
        matched_surface=top_surface if top_score >= stage2_candidate_floor else "",
        has_honorific=best_full_honorific or best_surname_honorific,
        is_recall_phrase=is_recall_phrase,
        destination=destination if top_score >= stage2_candidate_floor else "",
        instruction_plain="",
        transcript_masked=mask_transcript(cleaned_tx, "", kanji_name),
        candidate_for_stage2=(top_score >= stage2_candidate_floor),
        debug_details={
            "full_score": round(best_full_score, 4),
            "surname_score": round(best_surname_score, 4),
        },
    )
