# Head-to-Head Test: Yobareta vs Google Live Transcribe

Goal: get **measured** numbers for the pitch. Only claim a gap if this test shows it.

| File | Purpose |
| :--- | :--- |
| `calls.csv` | 26 test announcements: what to say, what should happen, which gap each one tests |
| `results_template.csv` | Copy to `results.csv` and fill in during the test |
| `score.py` | Prints the comparison table (`python eval/score.py eval/results.csv`) |

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

## 4. Score

```bash
python eval/score.py eval/results.csv
```

The table shows: true calls detected, surname-only calls noticed, **false full alarms** on other people, any alert on non-calls, correct destinations, and median latency.

## 5. What to claim in the pitch

- ✅ Claim a gap only if the numbers show it (e.g. *"0/6 false full alarms vs 3/6 for Live Transcribe on same-surname calls"*).
- ✅ Report latency honestly, even if Live Transcribe is faster. It streams continuously; Yobareta waits for the announcement to finish.
- ❌ Don't claim a gap the test didn't measure.

## Open question to settle with this test

**Restaurant calls (R01):** restaurants usually call **surname + party size only** (「2名様でお待ちのショウジ様」). Yobareta deliberately caps surname-only matches at **Possible** (2 short buzzes) to avoid false alarms in hospitals. If testers say that alert is too weak for restaurants, consider a party-size input: *surname + matching party size → Called*.
