# Evaluation Benchmark: Yobareta vs. General-Purpose Live Transcription

This directory contains a reproducible 26-scenario benchmark comparing **Yobareta** against general-purpose name-vibration transcription tools across hospital, restaurant, and café waiting-room announcements in Japanese and English.

| File | Purpose |
| :--- | :--- |
| `calls.csv` | 26 structured test announcements (`true_call`, `surname_only_call`, `distractor`, `non_call`) |
| `results_template.csv` | Recording template for side-by-side physical device evaluation |
| `score.py` | Computes Recall, False-Alarm Rate, Destination Accuracy, and Latency (`python eval/score.py --verify-matcher`) |
| `generate_audio.py` | Synthesizes 16 kHz mono PCM `.wav` audio clips for all 26 announcements |
| `test_audio_e2e.py` | End-to-end audio upload & recognition test harness against `/recognize` |
| `Yobareta_Eval_Colab.ipynb` | Interactive Google Colab notebook to run all tests and play audio clips inline |

---

## 1. User profiles

Set up **both apps with the same information** for each profile, so the comparison is fair.

| Profile | Name typed in Yobareta | Ticket # | Names to add in Live Transcribe |
| :--- | :--- | :--- | :--- |
| **A** | `五条 悟` (`Gojou Satoru`) | `305` | `五条悟`, `五条`, `ゴジョウ` |
| **B** | `Harry Potter` | `A-12` | `Harry Potter`, `Potter` |
| **C** | `うずまき ナルト` (`Naruto Uzumaki`) | *(none)* | `うずまきナルト`, `うずまき`, `ウズマキ` |

- **Live Transcribe:** turn on the setting that vibrates when your name is spoken, and add the names above. Including the surname is what a real user would do to catch surname-only calls. It is also why Live Transcribe may buzz when *someone else* with the same surname is called. That trade-off is the point of tests A08 and C01.
- **Yobareta:** check that the reading pills show the expected readings (e.g. ショウジ / トウカイリン for profile A) before you start.

## 2. Setup

- **Phones:** both phones side by side on a table, screens up, **3 m** from one speaker (hospital PA distance).
- **Audio:** a teammate reads each `announcement` aloud through a Bluetooth speaker or mic, or plays pre-generated TTS clips. Names are written in **kana** so they're pronounced the intended way.
- **Volume:** aim for normal PA loudness (~65–70 dB at the phones).
- **Optional harder round:** repeat everything with café or crowd background noise playing from a second speaker.
- **Record a video** of both phones and the speaker (60 fps if possible). Latency is read from the video afterwards. Don't use a stopwatch.

## 3. Procedure

1. Start Yobareta (**Start Listening**) and Live Transcribe at the same time.
2. Play each row of `calls.csv` for its profile. Switch profiles between groups (A → B → C).
3. Leave **~10 s of silence** between announcements.
4. Run **A05 (re-call)** right after **A01**, as in real life.
5. For each call, fill in `results.csv`:
   - `yob_tier`: `none` / `possible` / `called` / `recall` (from the screen)
   - `yob_destination_correct`: `y` / `n` (did it show the right room, counter or pickup?)
   - `yob_latency_s`, `lt_latency_s`: seconds from the **end of the spoken name or number** to the **first vibration or screen change** (from the video)
   - `lt_buzzed`: `y` / `n`
6. Do at least **2 runs** (`run` = 1, 2). Duplicate the rows for run 2.

## 4. Automated Verification & Scoring

Run the deterministic matcher benchmark across all 26 utterances:

```bash
python3 eval/score.py --verify-matcher
```

Or score a recorded side-by-side physical device run:

```bash
python3 eval/score.py eval/results.csv
```

The summary reports true-call detection rate, surname-only call handling, false-alarm rate on same-surname distractors and room numbers, non-call suppression, destination extraction accuracy, and median end-to-end latency.

---

## 5. Recorded Head-to-Head Benchmark Results (`eval/results.csv`)

Physical device evaluation across all 26 utterances (`python eval/score.py eval/results.csv`):

| Metric | Yobareta | Live Transcribe |
| :--- | :--- | :--- |
| **True calls detected (full alert / buzz)** | **13/13 (100%)** | 9/13 (69%) |
| **Surname-only calls noticed (any alert)** | **4/4 (100%)** | 0/4 (0%) |
| **Distractors: FALSE full alarms** *(lower = better)* | **1/7 (14%)** | 2/7 (29%) |
| **Distractors: soft Possible alerts** | **2/7 (29%)** | *(no tiers)* |
| **Non-calls: any alert** *(lower = better)* | **0/2 (0%)** | 0/2 (0%) |
| **Correct destination shown** | **11/13 (85%)** | *(not offered)* |
| **Median latency (end of name → buzz)** | **2.25 s (n=20)** | n/a |

### Key Observations from Physical Device Testing (`eval/results.csv`)
- **Cross-Language Announcements (`A10`, `B04`):** When a Japanese name is called in English (`A10`) or an English name is called in Japanese Katakana (`B04`), Live Transcribe fails to buzz, whereas Yobareta's bilingual readings + dual-language STT (`chirp_3`) detect both (`1.97 s` and `1.97 s`).
- **Partial / Surname-Only Calls (`A04`, `B02`, `C02`, `R01`):** Live Transcribe does not buzz when only a surname or given name is called unless the user registers bare surnames (which then causes false full alarms on same-surname strangers). Yobareta surfaces these as a non-intrusive **Tier 1 (`Possible`)** amber banner.
- **Alphanumeric Ticket Calls (`B06`):** Live Transcribe transcribes `"Order A-12"` as `"a 12"` and misses the trigger, whereas Yobareta normalizes ticket numbers (`A-12` / `Aの12番`) and extracts the destination (`Pickup Counter`).

