"""Deterministic Japanese + English reading & name matcher (SudachiPy Apache-2.0 + Latin phonetic).

Responsibilities:
1. Japanese: Tokenize STT transcripts into katakana readings via SudachiPy,
   normalize long vowels (ショウジ == ショージ, サトウ == サトー), strip honorifics
   (様/さん/殿/ちゃん/君), and compute sliding-window mora edit distance.
2. English / Latin script: Normalize case & phonetic variants (e.g. ph->f, sh->s,
   satoh->sato, shouji->shoji, stephen->steven), strip English titles (Mr./Ms./Dr.),
   and compute sliding-window token/character edit distance.
3. Exact number matching for ticket/order numbers in both Japanese and English
   while distinguishing destination room/counter/party numbers ("3番診察室",
   "Exam Room 3", "party of 2", "2名様") from caller ticket numbers ("305番", "Order 305").
4. Extract deterministic fallback destination & plain instructions in both languages,
   and mask bystander names in transcripts.
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

# Honorific suffixes in Japanese kanji/kana and katakana readings
_HONORIFIC_SURFACES = ("名様", "様", "さん", "さま", "殿", "ちゃん", "君", "くん", "氏")
_HONORIFIC_READINGS = ("メイサマ", "サマ", "サン", "ドノ", "チャン", "クン", "シ")

# English titles & honorifics
_ENGLISH_TITLES = {"mr", "mrs", "ms", "miss", "mx", "dr", "prof", "patient", "guest", "customer"}

# Re-call indicator phrases in Japanese and English waiting rooms
_RECALL_KEYWORDS_JA = (
    "再度",
    "もう一度",
    "お見えになりません",
    "いらっしゃいますか",
    "いらっしゃいませんか",
    "お呼びしております",
    "お呼びしています",
    "繰り返します",
)
_RECALL_KEYWORDS_EN = (
    "final call",
    "second call",
    "last call",
    "calling again",
    "paging again",
    "once again",
    "repeat call",
    "still waiting for",
)

# Destination patterns for deterministic extraction (Japanese + English)
_DESTINATION_PATTERNS: List[Tuple[re.Pattern[str], str, str]] = [
    # Japanese Hospital Exam Rooms
    (
        re.compile(r"(\d+|[一二三四五六七八九十]+)\s*番\s*(診察室|診|内診室|処置室|検査室|レントゲン室|CT室|MRI室)"),
        "Exam Room {num} ({raw})",
        "You were called → Exam Room {num}",
    ),
    # Japanese Counters / Windows
    (
        re.compile(r"(\d+|[一二三四五六七八九十]+)\s*番\s*(窓口|カウンター|受付|会計|お会計)"),
        "Counter {num} ({raw})",
        "You were called → Counter {num}",
    ),
    # English Exam / Consultation / Treatment Rooms
    (
        re.compile(
            r"\b(exam(?:ination)?\s*room|consultation\s*room|treatment\s*room|testing\s*room|room|door|bay)\s*#?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b",
            re.IGNORECASE,
        ),
        "Exam Room {num}",
        "You were called → Exam Room {num}",
    ),
    # English Counters / Windows / Desks
    (
        re.compile(
            r"\b(counter|window|desk|register|booth|station)\s*#?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b",
            re.IGNORECASE,
        ),
        "Counter {num}",
        "You were called → Counter {num}",
    ),
    # Japanese General Hospital Stations
    (
        re.compile(r"(受付|会計|お会計|薬局|お薬窓口|処方箋窓口|待合室|中待合)"),
        "{raw}",
        "You were called → Proceed to {raw}",
    ),
    # English General Hospital / Clinic Stations
    (
        re.compile(r"\b(reception|front\s*desk|check-?in|pharmacy|dispensary|cashier|billing|triage|lab|x-?ray)\b", re.IGNORECASE),
        "{raw}",
        "You were called → Proceed to {raw}",
    ),
    # Japanese Restaurant Seating
    (
        re.compile(r"(お席|テーブル|カウンター席|ご案内)"),
        "Seating / Host Stand ({raw})",
        "Your table is ready → Please see the host",
    ),
    # English Restaurant Seating
    (
        re.compile(r"\b(your\s+table\s+is\s+ready|table\s+is\s+ready|host\s+stand|seating\s+area|main\s+dining)\b", re.IGNORECASE),
        "Host Stand / Seating",
        "Your table is ready → Please see the host",
    ),
    # Japanese Cafe Pickup
    (
        re.compile(r"(受け取りカウンター|お渡し口|ピックアップ|カウンター)"),
        "Pickup Counter ({raw})",
        "Your order is ready → Pickup Counter",
    ),
    # English Cafe Pickup
    (
        re.compile(r"\b(pickup\s*counter|pick-?up\s*area|handoff\s*counter|collection\s*point|bar\s*counter)\b", re.IGNORECASE),
        "Pickup Counter",
        "Your order is ready → Pickup Counter",
    ),
]

# General catch-all destination extractors when a destination is outside the predefined list
_GENERIC_DEST_JA = re.compile(
    r"((?:\d+|[一二三四五六七八九十]+)階\s*の?\s*[一-龯々ァ-ヴーA-Za-z0-9]{1,14}|[一-龯々ァ-ヴーA-Za-z0-9]{2,14}(?:室|窓口|受付|コーナー|カウンター|席|ゲート|番線|エリア|ロビー|フロア|棟|番テーブル))\s*(?:へ|まで|に|でお待ち)"
)
_GENERIC_DEST_EN = re.compile(
    r"\b(?:proceed\s+to|come\s+to|go\s+to|report\s+to|head\s+to|waiting\s+at|ready\s+at)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9\s#-]{1,28}?)(?=\.|,|!|\s+please\b|$)",
    re.IGNORECASE,
)

# Contexts where a number refers to a destination room/counter or party size, NOT a ticket number
_NON_TICKET_NUMBER_SUFFIXES_JA = re.compile(
    r"^(\d+)\s*(?:番\s*(?:診察室|診|窓口|カウンター|受付|会計|テーブル|席|ゲート|ホーム|処置室|検査室)|名様|名|人|階|時|分|秒|歳|才)"
)
_NON_TICKET_NUMBER_PREFIXES_EN = re.compile(
    r"(?:exam(?:ination)?\s*room|consultation\s*room|treatment\s*room|room|door|window|counter|desk|table|booth|gate|floor|level|party\s+of|table\s+for|group\s+of)\s*#?\s*$",
    re.IGNORECASE,
)
_NON_TICKET_NUMBER_SUFFIXES_EN = re.compile(
    r"^\s*(?:people|persons|guests|pax|am|pm|o'clock|st|nd|rd|th\s+floor)\b",
    re.IGNORECASE,
)

_KANJI_DIGITS = {
    "〇": 0, "零": 0, "ゼロ": 0,
    "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9,
}
_ENGLISH_NUMBER_WORDS = {
    "zero": 0, "oh": 0,
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}

_FULLWIDTH_DIGIT_TRANS = str.maketrans(
    "０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ",
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
)


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
    """Normalizes katakana into canonical morae with long-vowel equivalence."""
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
    """Standard Levenshtein distance over sequence elements (morae or characters)."""
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
    """Normalized similarity in [0.0, 1.0]: 1.0 - (edit_distance / max_len)."""
    if not seq_a or not seq_b:
        return 0.0
    max_len = max(len(seq_a), len(seq_b))
    dist = mora_edit_distance(seq_a, seq_b)
    return max(0.0, 1.0 - (dist / max_len))


def normalize_latin_phonetic(word: str) -> str:
    """Normalizes an English/Romaji token so common phonetic spellings match.

    Examples:
    - 'Shouji' / 'Shoji' / 'Shohji' -> 'shoji'
    - 'Satoh' / 'Sato' / 'Satou' -> 'sato'
    - 'Stephen' / 'Steven' -> 'steven'
    - 'Smith' -> 'smith'
    """
    w = re.sub(r"[^a-z]", "", word.translate(_FULLWIDTH_DIGIT_TRANS).lower())
    if not w:
        return ""
    # Romaji / English long vowel & digraph normalizations
    w = w.replace("ph", "f")
    w = re.sub(r"([o])(?:u|h|o)", r"\1", w)
    w = re.sub(r"([u])(?:u|h)", r"\1", w)
    w = re.sub(r"([e])(?:i|e)", r"\1", w)
    w = re.sub(r"([a])(?:a|h)", r"\1", w)
    w = w.replace("ck", "k")
    # Collapse consecutive identical letters ('ll' -> 'l', 'ss' -> 's')
    w = re.sub(r"(.)\1+", r"\1", w)
    return w


def best_sliding_latin_match(
    transcript: str,
    target_reading: str,
) -> Tuple[float, str, bool]:
    """Matches an English / Romaji name against an English / Latin transcript.

    Returns:
        (best_score, matched_surface, has_title_or_honorific)
    """
    target_norm = target_reading.translate(_FULLWIDTH_DIGIT_TRANS).strip()
    if not re.search(r"[A-Za-z]", target_norm):
        return 0.0, "", False

    target_words_raw = [w for w in re.split(r"[^A-Za-z]+", target_norm) if w]
    target_words = [
        normalize_latin_phonetic(w)
        for w in target_words_raw
        if w.lower() not in _ENGLISH_TITLES and normalize_latin_phonetic(w)
    ]
    if not target_words:
        return 0.0, "", False

    # Extract word tokens with spans from the transcript
    tx_norm = transcript.translate(_FULLWIDTH_DIGIT_TRANS)
    tx_tokens: List[Tuple[str, str]] = []  # (surface, phonetic)
    for m in re.finditer(r"[A-Za-z]+", tx_norm):
        surf = m.group(0)
        phon = normalize_latin_phonetic(surf)
        if phon:
            tx_tokens.append((surf, phon))

    if not tx_tokens:
        return 0.0, "", False

    target_joined = "".join(target_words)
    k = len(target_words)
    best_score = 0.0
    best_start = 0
    best_end = 0

    n = len(tx_tokens)
    for start in range(n):
        if tx_tokens[start][0].lower() in _ENGLISH_TITLES:
            continue
        for win_len in range(max(1, k - 1), min(n - start, k + 1) + 1):
            end = start + win_len
            win_phon = "".join(tx_tokens[i][1] for i in range(start, end))
            score = mora_similarity(list(win_phon), list(target_joined))
            if score > best_score:
                best_score = score
                best_start = start
                best_end = end

    if best_score == 0.0:
        return 0.0, "", False

    matched_surface = " ".join(tx_tokens[i][0] for i in range(best_start, best_end))
    has_honorific = False
    if best_start > 0 and tx_tokens[best_start - 1][0].lower() in _ENGLISH_TITLES:
        has_honorific = True
    return best_score, matched_surface, has_honorific


def best_sliding_mora_match(
    transcript_tokens: Sequence[Tuple[str, str]],
    target_reading: str,
) -> Tuple[float, str, bool]:
    """Finds the highest-scoring Japanese mora window in `transcript_tokens` for `target_reading`."""
    target_morae = normalize_reading_morae(target_reading, strip_honorific=True)
    if not target_morae:
        return 0.0, "", False

    flat_morae: List[str] = []
    mora_to_token_idx: List[int] = []

    full_reading = "".join(r for _, r in transcript_tokens)
    if not full_reading:
        return 0.0, "", False

    for idx, (_, reading) in enumerate(transcript_tokens):
        token_morae = split_into_morae(reading)
        for mora in token_morae:
            flat_morae.append(mora)
            mora_to_token_idx.append(idx)

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

    rem_morae_str = "".join(flat_morae[best_end : best_end + 4])
    followed_by_honorific = any(rem_morae_str.startswith(h) for h in _HONORIFIC_READINGS)
    if not followed_by_honorific and end_tok + 1 < len(transcript_tokens):
        next_surface = transcript_tokens[end_tok + 1][0]
        followed_by_honorific = next_surface.startswith(_HONORIFIC_SURFACES)

    return best_score, matched_surface, followed_by_honorific


def _kanji_or_en_number_to_int(text: str) -> Optional[int]:
    """Converts Japanese kanji numerals or simple English number words into an integer."""
    if not text:
        return None
    cleaned = text.strip().lower()
    if cleaned.isdigit():
        return int(cleaned)
    if cleaned in _ENGLISH_NUMBER_WORDS:
        return _ENGLISH_NUMBER_WORDS[cleaned]

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
    """Normalizes a user-supplied ticket/order number (e.g. 'Ａ-３０５番' or 'Order #305' -> 'A305' or '305')."""
    if not raw:
        return ""
    norm = raw.translate(_FULLWIDTH_DIGIT_TRANS).strip().upper()
    norm = re.sub(
        r"(番号札|番号|ご注文番号|受付番号|ORDER\s*NUMBER|TICKET\s*NUMBER|ORDER|TICKET|NUMBER|NO\.?|#|番|様|さん|\s|-|ー)",
        "",
        norm,
        flags=re.IGNORECASE,
    )
    return norm


def match_ticket_number_in_transcript(transcript: str, ticket_number: Optional[str]) -> Tuple[bool, str]:
    """Checks for an exact ticket/order number match in Japanese or English transcripts.

    Carefully avoids matching room/counter numbers (e.g., '3番診察室', 'Exam Room 3', 'Counter 2')
    or party sizes ('2名様', 'party of 2', 'table for 4').
    """
    norm_target = normalize_ticket_number(ticket_number)
    if not norm_target:
        return False, ""

    norm_tx = transcript.translate(_FULLWIDTH_DIGIT_TRANS)
    norm_tx_upper = norm_tx.upper()

    # Check alphanumeric ticket numbers like "A12" or "B-05"
    alpha_num_match = re.match(r"^([A-Z]+)(\d+)$", norm_target)
    if alpha_num_match:
        prefix, digits = alpha_num_match.groups()
        pattern = re.compile(rf"\b{re.escape(prefix)}\s*[-ーの]?\s*0*{re.escape(digits.lstrip('0') or '0')}(?!\d)")
        m = pattern.search(norm_tx_upper)
        if m:
            return True, m.group(0)
        return False, ""

    if not norm_target.isdigit():
        return (norm_target in norm_tx_upper), (norm_target if norm_target in norm_tx_upper else "")

    target_int = int(norm_target)

    # 1. Check Arabic numerals in the normalized transcript
    for m in re.finditer(r"\d+", norm_tx):
        val = int(m.group(0))
        if val == target_int:
            prefix = norm_tx[: m.start()]
            tail = norm_tx[m.start() :]
            tail_after = norm_tx[m.end() :]
            # Reject Japanese room/counter/party size suffixes ("3番診察室", "2名様")
            if _NON_TICKET_NUMBER_SUFFIXES_JA.match(tail):
                continue
            # Reject English room/counter/party size prefixes ("Exam Room 3", "Counter 2", "party of 2")
            if _NON_TICKET_NUMBER_PREFIXES_EN.search(prefix):
                continue
            if _NON_TICKET_NUMBER_SUFFIXES_EN.match(tail_after):
                continue
            return True, m.group(0)

    # 2. Check Kanji numerals (e.g. "三百五番", "三〇五番", "十二番")
    for m in re.finditer(r"[〇零一二三四五六七八九十百千]+", norm_tx):
        val = _kanji_or_en_number_to_int(m.group(0))
        if val == target_int:
            tail_after = norm_tx[m.end() :]
            if re.match(r"^\s*(?:番\s*(?:診察室|診|窓口|カウンター|受付|会計|テーブル|席|処置室|検査室)|名様|名|人|階|時|分)", tail_after):
                continue
            return True, m.group(0)

    return False, ""


def extract_destination_deterministic(transcript: str) -> Tuple[str, str]:
    """Deterministic regex fallback to extract destination and plain instruction (JA + EN)."""
    if not transcript:
        return "", "You were called"

    norm_tx = transcript.translate(_FULLWIDTH_DIGIT_TRANS)
    for pattern, dest_tmpl, instr_tmpl in _DESTINATION_PATTERNS:
        m = pattern.search(norm_tx)
        if m:
            raw = m.group(0).strip()
            if m.lastindex and m.lastindex >= 2:
                # Group 1 or Group 2 may hold the room/counter number depending on JA vs EN pattern
                g1 = m.group(1)
                g2 = m.group(2)
                num_candidate = g1 if (_kanji_or_en_number_to_int(g1) is not None) else g2
                num_val = _kanji_or_en_number_to_int(num_candidate)
                num_str = str(num_val) if num_val is not None else num_candidate
                return dest_tmpl.format(num=num_str, raw=raw), instr_tmpl.format(num=num_str, raw=raw)
            return dest_tmpl.format(raw=raw), instr_tmpl.format(raw=raw)

    # Fallback: Open-ended catch-all for any destination noun phrase before 'へ/まで/に' or after 'proceed/come/go to'
    m_ja = _GENERIC_DEST_JA.search(norm_tx)
    if m_ja:
        dest_raw = m_ja.group(1).strip()
        return dest_raw, f"You were called → {dest_raw}"

    m_en = _GENERIC_DEST_EN.search(norm_tx)
    if m_en:
        dest_raw = m_en.group(1).strip()
        return dest_raw, f"You were called → {dest_raw}"

    return "", "You were called"


def mask_transcript(transcript: str, matched_surface: str, kanji_name: Optional[str] = None) -> str:
    """Masks bystander names in both Japanese and English transcripts to protect privacy."""
    if not transcript:
        return ""
    masked = transcript

    # 1. Japanese honorific masking (〇〇様 / 〇〇さん)
    def _replace_bystander_ja(match: re.Match[str]) -> str:
        prefix = match.group(1)
        honorific = match.group(2)
        if matched_surface and matched_surface in prefix:
            return f"{prefix}{honorific}"
        if kanji_name and kanji_name in prefix:
            return f"{prefix}{honorific}"
        return f"[***]{honorific}"

    masked = re.sub(r"([一-龯々ヶァ-ヴーぁ-ん]{1,8})(様|さん|さま)", _replace_bystander_ja, masked)

    # 2. English title masking (Mr. / Ms. / Mrs. / Dr. Firstname Lastname)
    def _replace_bystander_en(match: re.Match[str]) -> str:
        title = match.group(1)
        name_part = match.group(2)
        if matched_surface and matched_surface.lower() in name_part.lower():
            return match.group(0)
        if kanji_name and kanji_name.lower() in name_part.lower():
            return match.group(0)
        return f"{title} [***]"

    masked = re.sub(
        r"\b(Mr\.?|Mrs\.?|Ms\.?|Miss|Dr\.?)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)",
        _replace_bystander_en,
        masked,
    )
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
    """Runs the Stage 1 deterministic matcher on an STT transcript (Japanese + English)."""
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
    lower_tx = cleaned_tx.lower()
    is_recall_phrase = any(kw in cleaned_tx for kw in _RECALL_KEYWORDS_JA) or any(
        kw in lower_tx for kw in _RECALL_KEYWORDS_EN
    )
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

    kanji_clean = re.sub(r"\s+", "", kanji_name or "")
    tx_no_space = re.sub(r"\s+", "", cleaned_tx)

    # Combine explicit readings with kanji_name if kanji_name is Latin/English
    effective_full_readings = list(full_name_readings)
    if kanji_name and re.search(r"[A-Za-z]", kanji_name) and kanji_name not in effective_full_readings:
        effective_full_readings.append(kanji_name)

    # 2. Full name reading evaluation (Japanese Morae + English/Latin Phonetic)
    best_full_score = 0.0
    best_full_reading = ""
    best_full_surface = ""
    best_full_honorific = False

    for reading in effective_full_readings:
        if not reading or not reading.strip():
            continue
        mora_score, mora_surf, mora_hon = best_sliding_mora_match(tokens, reading)
        latin_score, latin_surf, latin_hon = best_sliding_latin_match(cleaned_tx, reading)

        if latin_score > mora_score:
            score, surface, has_hon = latin_score, latin_surf, latin_hon
        else:
            score, surface, has_hon = mora_score, mora_surf, mora_hon

        if kanji_clean and len(kanji_clean) >= 2 and kanji_clean.lower() in tx_no_space.lower():
            score = max(score, 1.0)
            surface = surface or (kanji_name or "").strip()

        if score > best_full_score:
            best_full_score = score
            best_full_reading = reading.strip()
            best_full_surface = surface
            best_full_honorific = has_hon

    # 3. Surname-only reading evaluation (Japanese Morae + English/Latin Phonetic)
    best_surname_score = 0.0
    best_surname_reading = ""
    best_surname_surface = ""
    best_surname_honorific = False

    for reading in surname_readings:
        if not reading or not reading.strip():
            continue
        mora_score, mora_surf, mora_hon = best_sliding_mora_match(tokens, reading)
        latin_score, latin_surf, latin_hon = best_sliding_latin_match(cleaned_tx, reading)

        if latin_score > mora_score:
            score, surface, has_hon = latin_score, latin_surf, latin_hon
        else:
            score, surface, has_hon = mora_score, mora_surf, mora_hon

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
