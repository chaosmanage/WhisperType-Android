# Long-Dictation Truncation — Implementation Plan

Status: **specified, partially applied, NOT complete.**
Branch: `feature/perf-overhaul`. Last green commit: `bc0c4e7` (`release(0.6.1)`).
Target release: **0.6.2 / versionCode 37**.

---

## ⚠️ Current tree state — do not build or push

`app/src/main/java/com/whispertype/android/platform/runtime/DictationCoordinator.kt`
is **mid-edit and does not compile**.

Applied already:
- `Config.echoQuietMs = 900` and `Config.echoStallMs = 2_500` (new knobs).
- `ActiveLiveSession.generationInFlight`, `.echoStalled`, `.echoStallJob` (new flags).
- `onTranscriptRevision(holder, isEcho)` signature change + new
  `restartEchoStallBackstop(holder)`.

Outstanding compile error:
- The single call site of `onTranscriptRevision` (inside `onLiveEvent`'s
  `TranscriptCandidates` branch, ≈ line 716) still passes one argument.
- `config.echoQuietMs` and `holder.generationInFlight` are declared but not yet
  read anywhere.

Full rollback to the green state:

```bash
git checkout -- app/src/main/java/com/whispertype/android/platform/runtime/DictationCoordinator.kt
```

---

## 1. The bug (root cause, proven from device logs)

`DictationCoordinator.reevaluateSettlement` settles with reason `ECHO_DEBOUNCE`
as soon as the echo has been quiet for `settleDebounceMs`, **without requiring
that the model actually finished generating**. The Gemini Live echo streams with
natural gaps of 300–600 ms (sentence boundaries, network jitter), so a pause is
misread as "the reply ended."

Consequence chain for a long dictation:

1. The app settles mid-reply and keeps only a partial echo.
2. `TranscriptCompleteness` correctly judges the echo "partial" and falls back to
   the raw ASR — for Hinglish that means the Devanagari→Latin transliteration
   *repair*, costing 0.5–1.9 s.
3. When the raw is also unusable (Hinglish) or the repair fails, all that remains
   is the truncated echo → **"only the first two words."**

### Evidence — seven consecutive device sessions (0.6.0, 2026-08-12 21:17–21:21)

`stopToTranscriptSettled` **must** be greater than `stopToLastEcho`. It was
inverted in 4 of 7 sessions, and those are exactly the 4 that needed a repair:

| # | audio | echo msgs | stopToLastEcho | stopToTranscriptSettled | generationComplete | settlePath | verdict |
|---|---|---|---|---|---|---|---|
| 1 | 11.7 s | 38 | 2962 | 3218 | true | ECHO_COMPLETE | ok |
| 2 | 8.8 s | 40 | 2921 | 3173 | true | ECHO_COMPLETE | ok |
| 3 | 14.8 s | 50 | **4211** | **2586** | true | ECHO_COMPLETE + `repair=1885ms` | **settled 1625 ms early** |
| 4 | 21.1 s | 8 | **1660** | **1356** | false | ECHO_PARTIAL_RAW + `repair=555ms` | **settled 304 ms early** |
| 5 | 7.8 s | 10 | **1497** | **1080** | false | ECHO_PARTIAL_RAW + `repair=668ms` | **settled 417 ms early** |
| 6 | 1.2 s | 2 | 876 | 1128 | false | ECHO_COMPLETE | ok |
| 7 | 8.5 s | 22 | **2516** | **1956** | true | ECHO_COMPLETE + `repair=816ms` | **settled 560 ms early** |

### Contributing factors

- **Self-inflicted regression.** `settleDebounceMs` was lowered 600 ms → 250 ms in
  0.6.0 for latency. `docs/GEMINI_LIVE.md` had calibrated 600 ms as *"above the
  measured ~90–300 ms inter-delta gaps."* 250 ms sits below the real gap
  distribution, so premature settlement became frequent.
- **`turnComplete` never arrives** — `turnComplete=false` in all 7 sessions.
- **`generationComplete` arrives only ~4/7** — in the other sessions the app has
  *no* completion signal and is settling purely on a silence guess.
- **`maxOutputTokens` is never sent.** `GeminiLiveWire.buildSetup` puts only
  `responseModalities` into `generationConfig`, so an unknown server-side output
  cap could independently truncate a long spoken reply. Unverified.
- **The raw ASR looks reliable**: `inputTranscriptMessages=2`,
  `firstInputToLastInputRevision=0–2 ms`, `stopToFirstInputRevision=248–458 ms` in
  every session regardless of length — it lands as one final text, fast. Its
  *completeness* on long turns is still unmeasured (no word-count metric exists).

---

## 2. Confirmed decisions

| Decision | Value | Rationale |
|---|---|---|
| Echo quiet window | **900 ms** | Above the measured 300–600 ms gap range |
| Echo stall backstop | **2500 ms** | Used only when no completion signal arrives |
| Raw quiet window | **250 ms** (unchanged `settleDebounceMs`) | The raw is single-shot, so a short window is safe |
| Hard deadline | 20 s (unchanged) | Ultimate bound |
| English MEDIUM/HIGH source | **Keep waiting for the polished echo** | User choice; raw-primary rejected |
| Fragment behaviour | **Fail with retry** | Already shipped in 0.6.1 |
| Latency trade | Accepted | Correctness over speed; segmentation buys it back |

---

## 3. Step 2 — settlement policy (the actual fix)

All in `platform/runtime/DictationCoordinator.kt`. Line numbers drift; the
function names are authoritative.

| # | Location | Change |
|---|---|---|
| **A** | `onLiveEvent`, `TranscriptCandidates` branch (≈ 716) | `onTranscriptRevision(holder)` → `onTranscriptRevision(holder, isEcho)`. `isEcho` is already in scope. **Fixes the compile error.** |
| **B** | `restartQuietDebounce` | Select the window: `val quietMs = if (holder.echoEnabled && holder.echoAccumulator.settledText()?.isNotBlank() == true) config.echoQuietMs else config.settleDebounceMs`, then `delay(quietMs)`. |
| **C** | `reevaluateSettlement` | Gate the echo branch: `holder.echoAccumulator.settledText()?.isNotBlank() == true -> if (!holder.generationInFlight \|\| holder.echoStalled) SettlementReason.ECHO_DEBOUNCE else null` |
| **D** | `afterActivityEnd` | `holder.generationInFlight = true; holder.echoStalled = false` — generation begins at the activity-end boundary. |
| **E** | `onTurnComplete`, `onGenerationComplete` | `holder.generationInFlight = false`. In `onInterrupted`, set it back to `true` (a cut generation may restart and must not read as finished). |
| **F** | `teardown` | `holder.echoStallJob?.cancel()`. |

Resulting policy:

- Completion signal received → settle after the **900 ms** quiet window.
  (`turnCompleteSeen` / `generationCompleteSeen` branches already precede the echo
  branch in `reevaluateSettlement`, so they win.)
- No completion signal → settle only after **2500 ms** of echo silence.
- No echo at all (echo disabled, or never arrived) → unchanged raw path
  (`sourceMissingGraceElapsed` / 250 ms).
- Hard 20 s deadline unchanged.

### Tests to add (`DictationCoordinatorTest`, virtual time)

1. **The regression test.** echo → 600 ms gap → echo → 600 ms gap → echo →
   `GenerationComplete` → advance past the quiet window. Assert the settled text
   contains **all** echo content (this is the exact failure being fixed).
2. Echo present, no completion signal: advancing 900 ms must **not** settle;
   advancing past 2500 ms settles via the backstop.
3. `GenerationComplete` settles at the 900 ms window (not earlier).
4. Echo-disabled / raw-only path still settles at 250 ms (0.6.0 behaviour intact).
5. `onInterrupted` re-arms `generationInFlight` so a gap after an interruption
   cannot settle.

### Tests to migrate (assume the old 250 ms echo settlement)

- `settlement is prohibited until activity end is queued`
- `generation complete is a hint and a later echo resets global quiet`
- `duplicate echo does not reset the global quiet debounce`
- `echo transcript is preferred over raw input at settlement`
- `an arriving echo prevents the fast raw fallback from settling`

Their `advanceTimeBy` values move from the 250 ms window to the 900 ms window.
Do **not** weaken an assertion to make a test pass — adjust only the timing.

---

## 4. Step 4 — send `maxOutputTokens`

- `platform/gemini/GeminiSessionConfig.kt`: add `val maxOutputTokens: Int? = 8192`.
- `platform/gemini/GeminiLiveWire.kt`, inside `buildSetup`'s `generationConfig`
  object: `config.maxOutputTokens?.let { put("maxOutputTokens", it) }`.
- `GeminiLiveWireTest`: assert the key is present by default and omitted when the
  config value is `null`.
- Deliberately **not** added to `WarmSessionProfile`: the value is constant for
  every session, so no profile divergence is possible and no warm session can be
  stale because of it.

Secondary benefit to verify on device: if an output cap was truncating replies,
lifting it should make `generationComplete` arrive more often, which reduces how
frequently the 2500 ms backstop is paid.

---

## 5. Step 3b — deferred (with reason)

Reusing the primary Live session for the Hinglish transliteration would remove a
~700 ms cold-connect per repair, but it is **not** a small change:
`OkHttpGeminiLiveSession.events()` returns `_events.receiveAsFlow()`, a
**single-consumer** flow already collected by the coordinator's `eventJob`.
`requestEchoFor` also collects `events()`, so reuse would steal events from the
coordinator. Doing it correctly requires converting the event channel to a
`SharedFlow` fan-out.

Deferred because Step 2 should make the repair path rare — the "partial echo"
that triggers it was caused by premature settlement in 4 of 7 sessions. Measure
after Step 2 before paying for the refactor.

---

## 6. Step 5 — segmentation (already built, off by default)

`Settings → Segment at pauses` (shipped in 0.6.0, default off). Short segments
produce fewer/shorter echo gaps and earlier completion signals, which is what
buys back the latency Step 2 spends. Requires the on-device check that
`activityHandling = NO_INTERRUPTION` genuinely lets segment N keep generating
while segment N+1 records.

---

## 7. Expected latency impact (projected against the real sessions)

| # | settled today | settled after fix | settle delta | repair removed | net end-to-end |
|---|---|---|---|---|---|
| 1 | 3218 | ~3862 | +644 ms | — | +644 ms |
| 2 | 3173 | ~3821 | +648 ms | — | +648 ms |
| 3 | 2586 (truncated) | ~5111 | +2525 ms | −1885 ms | **≈ +580 ms, now complete** |
| 4 | 1356 (truncated) | ~4160 (backstop) | +2804 ms | −555 ms | ≈ +2.3 s, now complete |
| 5 | 1080 (truncated) | ~3997 (backstop) | +2917 ms | −668 ms | ≈ +2.5 s, now complete |
| 7 | 1956 (truncated) | ~3416 | +1460 ms | −816 ms | **≈ +640 ms, now complete** |

Where a completion signal arrives, the eliminated repair pays back most of the
added wait. The worst case is a session with no completion signal at all
(≈ +2.5 s), which Step 4 and Step 5 are intended to reduce.

---

## 8. Verification

### Local gate (must be clean before pushing)

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Warnings are errors (`lint.warningsAsErrors` + Kotlin `allWarningsAsErrors`).

### Release chores (same commit as the behaviour change)

- `app/build.gradle.kts`: `versionCode = 37`, `versionName = "0.6.2"`.
- `CHANGELOG.md`: new `[0.6.2]` entry describing the truncation fix and the
  deliberate latency trade.
- `AGENTS.md`: update the "currently 0.6.1 / 36" version line.

### Device protocol (`docs/TESTING.md`)

Install the **release** APK — the phone's installed build is release-signed, so a
debug APK is rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`:

```bash
adb -s <SERIAL> install -r app/build/outputs/apk/release/app-release.apk
```

Run 3 × ~30 s and 2 × ~60 s dictations in **English MEDIUM** and again in
**Hinglish MEDIUM**, then:

```bash
adb -s <SERIAL> logcat -d | grep "SESSION DONE"
```

### Pass criteria

1. **`stopToTranscriptSettled` > `stopToLastEcho` in every session.** This single
   inequality was inverted in sessions 3, 4, 5, 7 and *is* the bug.
2. `settlePath=ECHO_COMPLETE` on long dictations; `repair=` absent or rare.
3. Zero `gemini_transcript_fragment` errors on genuine long dictations.
4. No duplicated head at the end of the transcript (0.6.1 restart guard holding).

### If criterion 1 still fails

Raise `echoQuietMs` toward 1200–1500 ms and re-measure, and add the deferred
`maxEchoGapMs` metric to `MutableSessionMetrics.recordEcho` (compute
`atNanos - lastEchoAt` before updating `lastEchoAt`, keep the max, emit it in
`summary()`) to measure the true gap distribution instead of estimating it.

---

## 9. Out of scope / known remaining debt

- `cleaned` tier of `TranscriptSelector` is dead (`ResultCandidate.cleaned` is
  `null` at all three production construction sites) — deletion deferred.
- Settlement policy still lives inside the ~1300-line `DictationCoordinator`;
  extraction into `core/transcript` deferred.
- Raw-ASR completeness on long turns is still unmeasured (no word-count metric).
- Segmentation remains unvalidated on a device.
