# Optimization Plan — WhisperType Android

> **Historical (0.6.x-era).** The perf-overhaul work described here shipped in
> 0.6.0–0.6.2 and was subsequently superseded: the echo architecture it
> optimized was **deleted in 0.10.0** when the app moved to the dedicated
> `gemini-3.5-transcribe-live` model (raw transcription, no echo barrier stack,
> no polish levels). Keep this document for history; current engine behavior is
> in `docs/GEMINI_LIVE.md`.

Branched from `main` as `feature/perf-overhaul` (WIP commit `35aa37f` + baseline
test fix `ba48716`). This document is the authoritative record of the perf and
correctness work, why each change exists, and how it is verified.

**Constraint (user-mandated):** the engine must stay on
`gemini-3.1-flash-live-preview` only. No other model, no other provider, no new
API key, no OpenRouter.

**Locked decisions:**
- Segmented activities ship behind an **off-by-default** setting.
- **MEDIUM** stays the default polish level; NONE/LOW get a fast path.
- No physical device is available for overlay/insertion/audio validation: those
  paths ship **device-pending** and must not be reported as verified.

---

## Background findings (root-cause analysis, verified in code)

1. **Slowness is architectural, not an API stall.** The dictation text is the
   model's *spoken reply* (`outputTranscription`) — the model must synthesize
   audio for every word, so finalization scales with speaking time (~0.6 s per
   sentence, ~3 s per 30 words, ~8 s per 100 words; `docs/GEMINI_LIVE.md`).
   Meanwhile the raw ASR (`inputTranscription`) is complete ~0.5 s after
   `activityEnd` at any length. The app discards that speed to wait for the echo.
2. **Duplicated-tail bug** (`TranscriptAccumulator.isAppendableDelta`): past
   4 content words, a cumulative *correction* is classified as new content and
   appended whole (proved by simulation: `"…tonight I need to order a pizza for
   delivery tonight around eight"`). The guard test passed only because its
   fixture was 3 words.
3. **Tap-to-record regression** (v0.5.6, `2d3c19e`): the Bluetooth source path
   blocks up to 2 s in a `Thread.sleep` SCO-poll loop before falling back to the
   phone mic (`AudioInputDevices.kt`). Additionally `runSession` resolves the
   session *before* starting capture, and prewarm requires a visible keyboard —
   so cold taps wait on key load (Keystore) with no warm session.
4. **Main-thread defects:** synchronous file read per `publish()` via
   `hasKey()`; `AudioRecord.stop()/release()` on main; unbounded
   `audioJob.join()`; history blob re-encrypt on main.
5. **Dead weight:** `_wip_uncommitted/` gitignored duplicate tree; the always-null
   `cleaned` tier in `TranscriptSelector`; `BoundedAudioQueue`; `GeminiEvent.Amplitude`;
   unreachable auto-VAD branch; 3 duplicate tokenizers; docs that describe 0.4.2
   while the app is 0.5.8.

---

## Phases

### Phase 0 — Baseline green
- Commit the user's in-flight WIP (`35aa37f`), fix the one broken WIP test
  (coroutine thread-name suffix, `ba48716`). ✅ done.

### Phase 1 — Tap-to-record regression
1. Skip the SCO path entirely when no Bluetooth input device is connected
   (cheap synchronous `listAvailable()` check first).
2. Replace the blocking `Thread.sleep` SCO poll with a suspending wait
   (`ACTION_SCO_AUDIO_STATE_UPDATED` broadcast + `withTimeout`), budget
   2000 ms → 400 ms.
3. Start capture **concurrently** with session resolution in `runSession`
   (the bounded 150-frame pre-ready buffer exists for exactly this).
4. Publish `Listening` as soon as the mic is hot.
5. Relax/remove the `keyboardVisible` prewarm-eligibility condition.
   - Verify: JVM tests; `tapToCapture=` on device (device-pending).

### Phase 2 — Duplicated-tail fix
- Replace the 4-word heuristic with a **maximal normalized-token overlap merge**
  (longest current-suffix == message-prefix splice). The in-flight WIP already
  moves in this direction; validate against the >4-word correction case and the
  dropped-word case (`"I want to go the store"`). Widen the 3-word guard test.
  - Verify: JVM tests.

### Phase 3 — Free finalize wins + main-thread hygiene
- `settleDebounceMs` 600 → 250.
- Clear `active` on insertion (kill the 1.2 s next-dictation lockout).
- Cache `hasKey()` (no file read per `publish()`).
- `AudioRecord.stop()/release()` off `Dispatchers.Main.immediate`.
- Bound `audioJob.join()` with a timeout.
- History blob write on `Dispatchers.IO`.
  - Verify: JVM tests + lint + assemble; jank fixes device-pending.

### Phase 4 — NONE/LOW instant path + warm-session invalidation
- NONE/LOW: `outputAudioTranscription = false`; settle on `inputTranscription`.
  Target ~0.7 s flat. MEDIUM/HIGH unchanged.
- Fix the pre-existing warm-session staleness bug: `createColdSession()` bakes
  the polish level/language into the prewarmed session but `resolveSession`
  reports the *current* language; invalidate the warm session when polish or
  speech mode changes, and carry the session's real configured language on
  `SessionResolution`.
  - Verify: JVM tests for settle policy + warm invalidation.

### Phase 5 — Segmented activities (flag, default OFF)
- Allow `ActivityEnded → ActivityStarted` (currently rejected).
- Set `realtimeInputConfig.activityHandling = NO_INTERRUPTION`.
- Segment at ≥700 ms silence using the existing amplitude detector.
- Deterministic per-segment stitching (no overlap heuristic).
- New off-by-default setting; toggleable on-device without a rebuild.
  - **Device gate:** only works if `NO_INTERRUPTION` lets segment N generate
    while segment N+1 records. Not verified here; ships OFF.

### Phase 6 — Dead code, structure, docs
- ✅ Delete `_wip_uncommitted/`.
- ✅ Delete `GeminiEvent.Amplitude` (never emitted) and its handler.
- ✅ Delete `CHUNK_DURATION_MS` (dead constant).
- ✅ Inline and delete `BoundedAudioQueue` (a thin Channel wrapper; `trySend`/`receive`
  were unused).
- ✅ Unify the selector's private tokenizer onto `TranscriptCompleteness.contentWords`.
- ✅ Correct `docs/GEMINI_LIVE.md` flat contradictions (echo is now the primary
  source; `outputAudioTranscription` on by default; NONE/LOW turns it off) plus a
  version-status banner; fix the `AGENTS.md` version line (0.6.0 / 35).
- ⏭ **Deferred (risky cosmetic, no runtime value):** deleting the always-null
  `cleaned` tier of `TranscriptSelector` (ripples through `ResultCandidate`,
  `TranscriptSelection`, `settle()`, and a large test file) and extracting the
  settlement policy out of `DictationCoordinator`. Both remain documented dead
  weight but are low-value churn on a green branch.

### Phase 7 — Release minification
- `isMinifyEnabled = true` with keep rules for kotlinx-serialization and the
  accessibility service.
  - Verify: `:app:assembleRelease` + lint.

---

## Per-change discipline
- One logical change per commit; scope-qualified imperative messages.
- Version bump (`versionCode` +1, `versionName`) + `CHANGELOG.md` entry in the
  same commit as any behavior change.
- Gate every phase: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  (warnings are errors).
- Never log transcripts, editor content, API keys, or the authenticated URL.
- Overlay/insertion/audio behavior changes are **device-pending** until a
  physical-device acceptance run per `docs/TESTING.md`.

## Verification boundary (stated plainly)
No device is available in this environment. JVM tests, lint, and assemble
prove logic; they do not prove mic timing, insertion reliability, or the
`activityHandling` behavior. Latency claims become real only when
`SESSION DONE tapToCapture= … stopToSettled=` is read off a device.
