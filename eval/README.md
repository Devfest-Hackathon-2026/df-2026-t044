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
| **A** | `東海林 太郎` | `305` | `東海林太郎`, `東海林`, `ショウジ` |
| **B** | `Alex Smith` | `A-12` | `Alex Smith`, `Smith` |
| **C** | `佐藤 太郎` | *(none)* | `佐藤太郎`, `佐藤` |

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
