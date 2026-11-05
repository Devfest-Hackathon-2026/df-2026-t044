"""Unit tests for the deterministic Japanese reading matcher and Stage 2 merge logic."""

from matcher import (
    evaluate_transcript,
    extract_destination_deterministic,
    match_ticket_number_in_transcript,
    mask_transcript,
    mora_similarity,
    normalize_reading_morae,
)
from stage2_gemini import Stage2Result, merge_stage1_and_stage2


def test_long_vowel_normalization():
    # ショウジ == ショージ
    assert normalize_reading_morae("ショウジ") == normalize_reading_morae("ショージ")
    # サトウ == サトー
    assert normalize_reading_morae("サトウ") == normalize_reading_morae("サトー")
    # オオノ == オーノ
    assert normalize_reading_morae("オオノ") == normalize_reading_morae("オーノ")
    # Honorific stripping
    assert normalize_reading_morae("ショウジサマ") == normalize_reading_morae("ショウジ")
    assert normalize_reading_morae("ショウジサン") == normalize_reading_morae("ショウジ")


def test_hospital_full_name_call_and_destination():
    res = evaluate_transcript(
        transcript="ショージタロウ様、3番診察室へお入りください",
        full_name_readings=["ショウジタロウ", "トウカイリンタロウ"],
        surname_readings=["ショウジ", "トウカイリン"],
        kanji_name="東海林太郎",
    )
    assert res.tier == "called"
    assert res.score >= 0.85
    assert res.destination == "Exam Room 3 (3番診察室)"
    assert res.instruction_plain == "You were called → Exam Room 3"


def test_surname_only_never_fires_full_alert():
    # Common surname like 佐藤 (サトウ) or 東海林 (ショウジ) alone must cap at 'possible'
    res = evaluate_transcript(
        transcript="サトー様、2番窓口へお越しください",
        full_name_readings=["サトウタロウ"],
        surname_readings=["サトウ"],
        kanji_name="佐藤太郎",
    )
    assert res.tier == "possible"
    assert res.match_type == "surname"
    assert res.score >= 0.90
    assert "Counter 2" in res.destination


def test_recall_escalation():
    res = evaluate_transcript(
        transcript="再度お呼びいたします。ショウジタロウ様、3番診察室へお入りください",
        full_name_readings=["ショウジタロウ"],
        surname_readings=["ショウジ"],
    )
    assert res.tier == "recall"
    assert res.instruction_plain.startswith("RE-CALL:")


def test_ticket_number_vs_room_number():
    # User has ticket #3, announcement calls someone else to Exam Room 3 ("3番診察室")
    matched, _ = match_ticket_number_in_transcript(
        "スズキ様、3番診察室へお入りください",
        ticket_number="3",
    )
    assert matched is False

    # User has ticket #305, announcement calls ticket 305 ("305番の方")
    matched_305, surface_305 = match_ticket_number_in_transcript(
        "ご注文番号305番のお客様、受け取りカウンターまでどうぞ",
        ticket_number="305",
    )
    assert matched_305 is True
    assert surface_305 == "305"


def test_restaurant_party_size_ignored_as_ticket():
    matched, _ = match_ticket_number_in_transcript(
        "2名様でお待ちのタナカ様、お席へご案内いたします",
        ticket_number="2",
    )
    assert matched is False


def test_bystander_name_masking():
    masked = mask_transcript(
        "山田花子様、1番診察室へ。東海林太郎様、3番診察室へお入りください",
        matched_surface="東海林太郎",
        kanji_name="東海林太郎",
    )
    assert "山田花子" not in masked
    assert "[***]様" in masked
    assert "東海林太郎様" in masked


def test_stage2_merge_respects_surname_cap_and_other_person():
    stage1_surname = evaluate_transcript(
        transcript="サトウ様、3番診察室へお入りください",
        full_name_readings=["サトウタロウ"],
        surname_readings=["サトウ"],
    )
    assert stage1_surname.tier == "possible"

    # Even if Stage 2 returns high confidence addressed_to_user=True on surname-only,
    # it MUST remain 'possible' and never escalate to 'called'.
    s2_high = Stage2Result(
        addressed_to_user=True,
        confidence=0.99,
        call_type="new_call",
        destination="Exam Room 3 (3番診察室)",
        instruction_plain="You were called → Exam Room 3",
    )
    merged = merge_stage1_and_stage2(stage1_surname, s2_high)
    assert merged.tier == "possible"

    # If Stage 2 detects another person with the same surname ("佐藤花子様"), downgrade to 'none'
    s2_other = Stage2Result(
        addressed_to_user=False,
        confidence=0.95,
        call_type="other_person",
        destination="Exam Room 3",
        instruction_plain="",
    )
    merged_other = merge_stage1_and_stage2(stage1_surname, s2_other)
    assert merged_other.tier == "none"
