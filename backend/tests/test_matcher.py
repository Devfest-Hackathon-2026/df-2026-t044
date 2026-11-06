"""Unit tests for the deterministic Japanese + English reading matcher and Stage 2 merge logic."""

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


def test_english_hospital_full_name_and_surname_cap():
    # Full English name -> 'called'
    res_full = evaluate_transcript(
        transcript="Mr. Alex Smith, please proceed to Exam Room 3.",
        full_name_readings=["Alex Smith", "アレックススミス"],
        surname_readings=["Smith", "スミス"],
        kanji_name="Alex Smith",
    )
    assert res_full.tier == "called"
    assert res_full.score >= 0.85
    assert res_full.destination == "Exam Room 3"
    assert res_full.instruction_plain == "You were called → Exam Room 3"

    # English surname-only -> capped at 'possible', never 'called'
    res_surname = evaluate_transcript(
        transcript="Mr. Smith, please come to Counter 2.",
        full_name_readings=["Alex Smith"],
        surname_readings=["Smith"],
        kanji_name="Alex Smith",
    )
    assert res_surname.tier == "possible"
    assert res_surname.match_type == "surname"
    assert res_surname.destination == "Counter 2"


def test_english_recall_and_room_number_guard():
    # English re-call phrase escalates to 'recall'
    res_recall = evaluate_transcript(
        transcript="Final call for Alex Smith, please proceed to Exam Room 3.",
        full_name_readings=["Alex Smith"],
        surname_readings=["Smith"],
    )
    assert res_recall.tier == "recall"
    assert res_recall.instruction_plain.startswith("RE-CALL:")

    # English room number ("Exam Room 3") must NOT false-trigger ticket_number="3"
    matched_room, _ = match_ticket_number_in_transcript(
        "Mr. Johnson, please proceed to Exam Room 3.",
        ticket_number="3",
    )
    assert matched_room is False

    # English order number ("Order number 305") matches ticket_number="305"
    matched_order, surf = match_ticket_number_in_transcript(
        "Order number 305 is ready at the pickup counter.",
        ticket_number="305",
    )
    assert matched_order is True
    assert surf == "305"


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
    matched, _ = match_ticket_number_in_transcript(
        "スズキ様、3番診察室へお入りください",
        ticket_number="3",
    )
    assert matched is False

    matched_305, surface_305 = match_ticket_number_in_transcript(
        "ご注文番号305番のお客様、受け取りカウンターまでどうぞ",
        ticket_number="305",
    )
    assert matched_305 is True
    assert surface_305 == "305"


def test_room_number_with_goushitsu_ignored_as_ticket():
    # Ticket #305 must not fire on a ward room number ("305号室")
    matched, _ = match_ticket_number_in_transcript(
        "305号室のスズキ様、処置室へお越しください",
        ticket_number="305",
    )
    assert matched is False


def test_restaurant_party_size_ignored_as_ticket():
    matched, _ = match_ticket_number_in_transcript(
        "2名様でお待ちのタナカ様、お席へご案内いたします",
        ticket_number="2",
    )
    assert matched is False

    matched_en, _ = match_ticket_number_in_transcript(
        "Party of 2 for Tanaka, your table is ready.",
        ticket_number="2",
    )
    assert matched_en is False


def test_bystander_name_masking():
    masked = mask_transcript(
        "山田花子様、1番診察室へ。東海林太郎様、3番診察室へお入りください",
        matched_surface="東海林太郎",
        kanji_name="東海林太郎",
    )
    assert "山田花子" not in masked
    assert "[***]様" in masked
    assert "東海林太郎様" in masked

    masked_en = mask_transcript(
        "Mr. John Doe, Room 1. Mr. Alex Smith, please proceed to Exam Room 3.",
        matched_surface="Alex Smith",
        kanji_name="Alex Smith",
    )
    assert "John Doe" not in masked_en
    assert "Mr. [***]" in masked_en
    assert "Alex Smith" in masked_en


def test_stage2_merge_respects_surname_cap_and_other_person():
    stage1_surname = evaluate_transcript(
        transcript="サトウ様、3番診察室へお入りください",
        full_name_readings=["サトウタロウ"],
        surname_readings=["サトウ"],
    )
    assert stage1_surname.tier == "possible"

    s2_high = Stage2Result(
        addressed_to_user=True,
        confidence=0.99,
        call_type="new_call",
        destination="Exam Room 3 (3番診察室)",
        instruction_plain="You were called → Exam Room 3",
    )
    merged = merge_stage1_and_stage2(stage1_surname, s2_high)
    assert merged.tier == "possible"

    s2_other = Stage2Result(
        addressed_to_user=False,
        confidence=0.95,
        call_type="other_person",
        destination="Exam Room 3",
        instruction_plain="",
    )
    merged_other = merge_stage1_and_stage2(stage1_surname, s2_other)
    assert merged_other.tier == "none"


def test_open_ended_custom_destinations_ja_and_en():
    # Unlisted Japanese destination ("2階の採血コーナー")
    res_ja = evaluate_transcript(
        transcript="ショージタロウ様、2階の採血コーナーへお越しください",
        full_name_readings=["ショウジタロウ"],
        surname_readings=["ショウジ"],
    )
    assert res_ja.tier == "called"
    assert res_ja.destination == "2階の採血コーナー"
    assert res_ja.instruction_plain == "You were called → 2階の採血コーナー"

    # Unlisted English destination ("4th Floor MRI Suite")
    res_en = evaluate_transcript(
        transcript="Mr. Alex Smith, please proceed to the 4th Floor MRI Suite.",
        full_name_readings=["Alex Smith"],
        surname_readings=["Smith"],
    )
    assert res_en.tier == "called"
    assert res_en.destination == "4th Floor MRI Suite"
    assert res_en.instruction_plain == "You were called → 4th Floor MRI Suite"

