# Implementation Plan 3 — "Experience & Reach" (0.4.0)

**Branch:** `feature` (created from `main`; merged to `main` **only** when the user explicitly asks)
**Version target:** 0.4.0 (versionCode 23)
**Date:** 2026-08-06
**Status:** Approved plan; execution happens on `feature` in modular, sub-agent-driven waves.

---

## 1. Purpose

WhisperType's transcription engine (releases A–F + 0.3.1) is stable and
device-verified. This plan ships the next product layer:

1. **Android 13+ compatibility** — the app currently requires Android 14
   (minSdk 34) and the user's Android 13 tablet cannot install it.
2. **Movable bubble** — the user can touch and drag the mic bubble to **any**
   position on screen (fits any keyboard/app layout, personal readability).
3. **Capsule listening UI** — replace the large rectangle panel with a compact
   capsule: Stop + Cancel and an animated circular waveform.
4. **Auto-stop timeout** — silence-based auto-stop **and** a hard recording cap
   (whichever fires first), user-selectable: 15 / 30 / 60 / 120 / 300 s, default 60 s.
5. **History** — a real, viewable, encrypted transcript history: list, copy,
   delete one, delete all, retention-days respected. (Today the toggle exists
   but nothing records and nothing can be viewed.)
6. **Custom dictionary** — correction rules only: the user adds words with an
   optional "always write as" spelling; the app replaces the ASR output at
   insertion. 100% client-side, offline, reliable.
7. **Polish levels** — four transcription styles passed through the
   `systemInstruction`: **NONE (raw) / LOW / MEDIUM / HIGH**, default **MEDIUM**.
8. **Documentation overhaul** — remove obsolete docs, update the keepers, and
   write the exhaustive Gemini Live wire mechanics reference.

**Hard constraint (user-mandated):** only the **Gemini 3.1 Flash Live Preview**
model via the Live WebSocket API. No other models, no REST `generateContent`
fallback, no text-modality workaround.

**Execution model (user-mandated):** build everything first — every module, every
integration, every UI screen, every test, docs, and the full JVM gate
(test + lint + assemble) — with **no device required at any point during
development**. All on-device testing is consolidated into **one final phase
(§13. Phase 10 — Device validation) that runs only after all development is
complete**, when the user connects the phone/tablet. This lets the entire
development run overnight unattended; nothing in Phases 1–9 requires a connected
device.

---

## 2. Reference research — open-typeless (`tover0314-w/opentypeless`)

A desktop (Tauri/Rust/TS) voice-input app: hotkey → record → STT → optional LLM
polish → type into any app. Feature mapping for this plan:

| Open-typeless feature | Decision | How it maps here |
| --- | --- | --- |
| Raw → AI polish (separate LLM call) | **Adapt** | We cannot make a second text LLM call (Live-only; the voice model rejects TEXT modality). Polish is done via `systemInstruction` that biases the Live transcription itself (§8). |
| Custom dictionary + correction rules | **Adopt (correction rules only)** | Client-side word → correction replacement applied at insertion (§9). |
| Local history with search | **Adopt** | History screen with list / copy / delete / delete-all; retention; encrypted storage (§10). |
| Capsule UI + floating states + waveform | **Adopt** | Capsule panel + animated circular waveform (§6). |
| Idle auto-hide | Already present | Bubble visible only when eligible. |
| App-aware writing / Ask Anything / translation / scenes | **Defer** | Out of scope for 0.4.0; revisit after the core experience ships. |

---

## 3. Phase 0 — Branch and baseline

1. `git checkout main && git checkout -b feature` (already done for this plan; push the branch so work is backed up).
2. Write this document as `docs/IMPLEMENTATION_PLAN_3.md` — the first commit on `feature`.
3. Baseline gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` green on `feature` (device-free).
4. **No device baseline** — device verification is deferred entirely to Phase 10.

---

## 4. Phase 1 — Android 13 support (minSdk 33)

### 4.1 Confirmed blockers in the current code

| Blocker | Location | Why it breaks Android 13 |
| --- | --- | --- |
| `minSdk = 34` | `app/build.gradle.kts` | Android 13 is API 33; APK refuses to install (`INSTALL_FAILED_OLDER_SDK`). |
| `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | `FlowRuntimeService.kt` (onCreate + dictation promotion) | API 34 constant/type; passing unknown type bits on API 33 is risky and must be SDK-guarded. |
| `android:foregroundServiceType="specialUse\|microphone"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | `AndroidManifest.xml` | `specialUse` and the property are API 34; lint `NewApi` fails with minSdk 33 unless `tools:targetApi="34"` is applied. |
| No runtime permission requests anywhere | `MainActivity.kt` | `POST_NOTIFICATIONS` (API 33+) and `RECORD_AUDIO` are runtime permissions; on the S25 they were granted manually. A fresh Android 13 install needs a real request flow. |

### 4.2 Changes

1. **`app/build.gradle.kts`** — `minSdk 33`.
2. **`AndroidManifest.xml`**
   - Keep `foregroundServiceType="specialUse|microphone"` with `tools:targetApi="34"` on the service element.
   - Add `tools:targetApi="34"` to the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property element.
   - Keep the `FOREGROUND_SERVICE_*` permission strings (unknown permissions are inert on older API levels).
3. **`FlowRuntimeService.kt`** — introduce one `FgsTypes` helper:
   - `>= 34`: `SPECIAL_USE or MICROPHONE` (current behavior).
   - `33`: `MICROPHONE` only **after** RECORD_AUDIO is confirmed; for the pre-dictation overlay phase pass `SPECIAL_USE` (inert bit on API 33).
   - **Empirically verified on the Android 13 tablet.** Documented fallback if API 33 rejects the inert bit: use the two-argument `startForeground(id, notification)` for the pre-permission phase (the manifest-declared type is used).
4. **`MainActivity.kt`** — runtime permission flow:
   - Request `RECORD_AUDIO` and `POST_NOTIFICATIONS` (API 33+) on launch when missing, with rationale text.
   - Re-request on resume if revoked; reflect state in the home status card.
5. **SDK sweep** — grep the whole main source for other API 34+ usage during execution (e.g., `registerReceiver` flags, `MediaProjection`, notification/`PendingIntent` flags) and guard or backport as needed.

### 4.3 Acceptance (Phase 1 gate)

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` green (device-free;
  lint confirms the `tools:targetApi` handling of the `specialUse` FGS type).
- Android 13 runtime behavior (permission flow, FGS type guards) is covered by
  JVM tests where extractable; **on-device verification is deferred to
  Phase 10** on the Android 13 tablet.

---

## 5. Phase 2 — Movable bubble (free touch-drag anywhere)

### 5.1 Behavior (user-confirmed)

- **Touch and drag the bubble to ANY position** on screen. No snapping, no
  anchoring, no keyboard-boundary constraint.
- Only constraint: the bubble stays **fully on-screen** (clamped).
- **Tap (no movement past touch slop) = start dictation**; **drag = move**.
  Same bubble; disambiguated by gesture distance.
- Position **persists** across sessions; Settings row **"Reset bubble position"**
  restores the default right-edge placement.

### 5.2 Design

1. **Pure placement logic** — new `core/overlay/BubblePlacement` (or extend
   `OverlayPlacement`):
   - Inputs: saved position `(x: Float?, y: Float?)` in dp (null = default),
     display metrics, window size.
   - Output: clamped pixel `(x, y)`; helper `clamp(position, display, window)`.
   - Host-testable: clamping at all four screen edges, null → default.
2. **`PersistentOverlayHost`**:
   - `buildLayoutParams` switches from `gravity = END or CENTER_VERTICAL` to
     `gravity = TOP or START` with explicit `x/y` when a position is saved.
   - New API: `moveBy(dxPx, dyPx)` / `setPosition(x, y)` → recompute clamped
     params and call `wm.updateViewLayout(view, params)` (main-thread Handler,
     existing pattern).
   - Persist final position (debounced) through a new host→service callback or
     direct `SettingsRepository` access supplied at construction.
3. **`WhisperTypeOverlayContent`** — `IdleBubble`:
   - Outer `pointerInput { detectDragGestures(...) }` emitting deltas to the host;
     inner `pointerInput { detectTapGestures { onIntent(START_DICTATION) } }`.
   - Drag only consumes after movement exceeds touch slop so taps still start.
   - New `onDrag: (dx: Float, dy: Float) -> Unit` parameter threaded from the host.
4. **Settings** — keys `bubbleX`, `bubbleY` (nullable); `resetBubblePosition()`.

### 5.3 Tests

- `BubblePlacementTest`: clamp at edges, null default, dp→px, window larger than display.
- Overlay content test (existing OverlayHostStateMachine/Visibility suites): drag
  emits deltas, tap still emits START after a drag with zero movement.

---

## 6. Phase 3 — Capsule listening UI + animated circular waveform

### 6.1 Behavior

- Replace the large `ListeningPanel` rectangle with a **compact capsule**
  (pill shape, `RoundedCornerShape(50)`):
  - **Animated circular waveform** — a ring of radial bars driven by the live
    `Listening.amplitude` (already throttled ~16.7 Hz) with a smooth
    `rememberInfiniteTransition` pulse when idle → clearly signals recording.
  - **Stop** (primary) and **Cancel** icon buttons — exactly two actions.
- `Starting` reuses the capsule with the connecting state (small "connecting"
  indicator). `Finalizing` / `Inserting` stay compact status pills.
- Keep test tags: `wt_panel`, `wt_stop`, `wt_cancel`.
- Optional: show elapsed seconds (or remaining auto-stop time) on the capsule —
  `DictationState.Listening.elapsedMillis` already exists.

### 6.2 Design

1. New `CircularWaveform` composable (pure, AOT-safe, preview-friendly):
   - `Canvas` drawing N radial bars around a circle; bar length =
     `amplitude`-driven base + animated pulse phase; colors from
     `WhisperTypeColors`.
   - No looping animation when not recording (reduced-motion safe, per §17.4 of
     the PRD — keep the existing static fallback principle).
2. `WhisperTypeOverlayContent` — new `ListeningCapsule` + `StartingCapsule`.
3. Theme — verify a small set of new color roles or reuse existing accents.

### 6.3 Tests

- Compose UI smoke via existing instrumented patterns is optional (device tier);
  JVM tests cover the pure amplitude→bar-length mapping helper if extracted.

---

## 7. Phase 4 — Auto-stop timeout (silence + cap, whichever first)

### 7.1 Behavior (user-confirmed)

- New setting `autoStopSeconds` with options **15 / 30 / 60 / 120 / 300**,
  **default 60**.
- **Both combined**: a hard recording cap (N seconds of Listening) **and** a
  silence auto-stop (no speech for N seconds), whichever fires first.
- Auto-stop follows the exact same path as a user STOP: orderly capture drain,
  `activityEnd`, settle, insert.

### 7.2 Design

1. **Settings** — key `autoStopSeconds` (Int), options list in the UI.
2. **`DictationCoordinator`**:
   - New `Config` knobs: `autoStopSeconds: Long = 60`, `speechAmplitudeThreshold: Float = 0.02f`.
   - During `Listening`, one `autoStopJob`:
     - **Cap arm**: `delay(autoStopSeconds)` → `stop()`.
     - **Silence arm**: watch `capture.amplitude`; when `level >= threshold`
       reset the silence deadline; when silence exceeds the timeout → `stop()`.
   - The job is cancelled by the existing teardown; never fires outside
     `Listening`; auto-stop result goes through the normal finalization
     (debounce/deadline) so exactly-one insertion holds.
3. The amplitude flow is already ~16.7 Hz — fine for silence detection
   (150 ms granularity).

### 7.3 Tests (virtual time)

- Cap fires at N and finalizes once.
- Silence fires at N; speech resets the silence timer (extend past N with speech).
- Never fires before Listening (during Starting/connecting).
- Auto-stop with a transcript → exactly one insertion (via debounce), with the
  auto-stop path indistinguishable from a user STOP.

---

## 8. Phase 5 — Polish levels (NONE / LOW / MEDIUM / HIGH)

### 8.1 Behavior (user-confirmed)

- Four transcription styles passed through the `systemInstruction` (the only
  prompt lever available; the Live voice model cannot post-process text):
  - **NONE** — completely raw: transcribe verbatim as spoken; no added
    punctuation, capitalization, grammar, or wording changes.
  - **LOW** — light cleanup: basic sentence punctuation and capitalization;
    keep exact words and natural spoken phrasing.
  - **MEDIUM** (default) — clean written text: proper punctuation,
    capitalization, and standard grammar; keep the user's words and meaning.
  - **HIGH** — completely polished, well-structured text: correct grammar,
    proper punctuation/capitalization, clear sentence structure, logical
    organization (paragraphs, lists where appropriate); keep meaning and words
    wherever possible.
- Composed with the **Hinglish Latin-script rule** when the language mode is
  HINGLISH (never Devanagari).

### 8.2 Design

1. `core/model/TranscriptionStyle.kt` — `enum class TranscriptionStyle { NONE, LOW, MEDIUM, HIGH }`.
2. `LanguageMode.liveInstruction(style: TranscriptionStyle)`:
   - Draft texts (final wording at implementation, verified by tests):
     - NONE: "Transcribe the user's speech verbatim, exactly as spoken. Do not add or change punctuation, capitalization, grammar, or wording. Output only the words spoken."
     - LOW: "Transcribe the user's speech with light cleanup: add basic sentence punctuation and capitalization, but keep the exact words and natural spoken phrasing."
     - MEDIUM: "Transcribe the user's speech into clean written text: proper punctuation, capitalization, and standard grammar, while keeping the user's words and meaning."
     - HIGH: "Transcribe the user's speech into polished, well-structured written text: correct grammar, proper punctuation and capitalization, clear sentence structure, and logical organization, with paragraphs and lists where appropriate. Keep the user's meaning and words wherever possible."
   - HINGLISH mode prepends/appends: "Write Hindi words in Latin script (romanized Hindi / Hinglish), never in Devanagari script. Keep English words and phrases exactly as spoken. Output only the transcription, nothing else."
3. `GeminiSessionConfig` — new field `transcriptionStyle: TranscriptionStyle = MEDIUM`; `GeminiLiveWire.buildSetup` sends `systemInstruction` from `config.transcriptionStyle` (wire shape unchanged).
4. **Settings** — key `polishLevel` (enum name), 4-option selector; the service passes `cachedPolishLevel` when building session config (cold + warm paths).
5. **Documented caveat** — instruction-based styles are ASR biases, not guarantees; the device matrix records how distinguishable the levels are.

### 8.3 Tests

- `LanguageModeTest`: 8 variants (4 levels × English/Hinglish) assert the exact instruction strings (including the Hinglish Latin rule composition).
- Wire test: `buildSetup` carries the style instruction when set; no instruction for NONE on English.
- Settings repository test for the new key.

---

## 9. Phase 6 — Custom dictionary (correction rules only)

### 9.1 Behavior (user-confirmed)

- The user adds words with an optional **"always write as"** spelling.
- The app replaces the ASR output with the corrected spelling at **insertion**
  time — 100% client-side, offline, reliable.
- **No prompt injection** (correction rules only).

### 9.2 Design

1. Pure module `core/dictionary/DictionaryCorrections`:
   - `data class DictionaryEntry(match: String, replace: String)`.
   - `apply(text: String, entries: List<DictionaryEntry>): String` —
     word-boundary, case-insensitive matching; longest match first; no
     replacement when no match; punctuation-preserving.
   - Host-testable.
2. **Settings storage** — DataStore key `dictionary` (JSON via kotlinx-serialization):
   - `SettingsRepository.dictionary(): Flow<List<DictionaryEntry>>`,
     `addDictionaryEntry(entry)`, `removeDictionaryEntry(match)`, `clearDictionary()`.
3. **Application point** — `FlowRuntimeService.sendInsertion(...)` applies the
   cached dictionary corrections to the final text before IPC insert (host side,
   keeps `DictationCoordinator` pure).
4. **UI** — Settings "Custom dictionary" section: add (word + optional
   correction), list entries (term → correction), delete per entry, clear all.

### 9.3 Tests

- `DictionaryCorrectionsTest`: case-insensitive, word boundaries ("cat" doesn't
  match "category"), longest-match-first, no-op when no match, mixed punctuation.
- Settings repository test (persistence round-trip).

---

## 10. Phase 7 — History (viewable, encrypted)

### 10.1 Behavior

- History becomes real: entries store the **transcript text** (opt-in,
  encrypted, retention-controlled).
- **History screen**: newest-first list (text preview, language badge, date);
  per-item **Copy** and **Delete**; top-bar **Delete all** and back.
- Settings: existing enable toggle + retention slider; new **"View history"** row
  (visible when enabled).
- **Toggling history off stops recording; existing entries are kept** (Delete All
  is one tap).
- Recording hook: session end (outcome, language, settled text).

### 10.2 Design

1. **`HistoryRepository` redesign**:
   ```kotlin
   data class HistoryEntry(
       val id: String,
       val timestampMillis: Long,
       val text: String,
       val language: String,
       val charCount: Int,
       val outcome: String,
   )
   fun events(): Flow<List<HistoryEntry>>      // newest first, retention-pruned
   suspend fun record(entry: HistoryEntry): Boolean
   suspend fun delete(id: String): Boolean
   suspend fun clear(): Boolean
   // prune-on-write by retentionDays; hard cap ~500 entries
   ```
2. **`EncryptedHistoryRepository`** — reuses the proven secrets layer
   (`KeystoreKeyStore` + `AesGcmCipher` + `BlobStore`, the `SecretStore` pattern):
   one encrypted JSON blob in app-private storage; constructor takes the
   retention-days provider; host-testable with fake keystore/cipher/blob.
3. **Wiring**:
   - `DictationHost.onSessionFinished(state, metrics)` gains
     `transcript: String?` (the settled text when one existed — the coordinator
     already holds `accumulator.settledText()`).
   - `FlowRuntimeService` records a history entry when `historyEnabled` (cached
     like `cachedSpeechMode`): outcome from the terminal state, language from the
     session, text from the callback.
4. **UI** — new `ui/history/HistoryScreen.kt`; `MainActivity` gains a third route
   (Home / Settings / History) with back navigation; Settings row navigates.

### 10.3 Tests

- Repository: record/read ordering, delete, clear, retention pruning, size cap,
  encryption round-trip (pattern: `SecretCipherTest` / `ClientInvalidRecoveryTest`).
- Coordinator: `onSessionFinished` carries the transcript only when one exists.
- Service wiring: recording honors the enable flag and never throws when history
  storage is absent.

---

## 11. Phase 8 — Documentation overhaul + Gemini wire reference

### 11.1 Remove (obsolete / historical / superseded)

- Root `Implementation_Plan.md`, root `FailureReport.md` (old prototype docs).
- `docs/FAILURE_TRANSCRIPTION.md` (superseded by the engine docs).
- `docs/TRANSCRIPTION_REMEDIATION_PLAN.md` (superseded; its outcome is documented
  in `GEMINI_LIVE_TRANSCRIPTION.md`).
- `docs/REBUILD_DECISION_RECORD.md`, `docs/REBUILD_FAILURE_REPORT.md`,
  `docs/REBUILD_PROGRESS.md` (rebuild-era history).
- `docs/WISPR_REVERSE.md`, `docs/BEHAVIOR_MATRIX.md` (verify content; remove if
  stale).
- `_wip_uncommitted/` directory (verify tracked/ignored state first; remove or
  gitignore).

### 11.2 Keep & update

- `README.md` (0.4.0 features, Android 13+, new settings).
- `CHANGELOG.md` (0.4.0 entry).
- `docs/USER_SETUP.md` (new settings: timeout, polish, dictionary, history, bubble reset).
- `docs/PUSH_TO_DEVICE.md` (Android 13 tablet reference + 0.4.0).
- `docs/ON_DEVICE_TEST_PROTOCOL.md` (new stages: auto-stop, polish levels, dictionary, history, bubble).
- `docs/TESTING.md` (new test inventory).
- `docs/TROUBLESHOOTING.md` (new failure modes).
- `docs/SECURITY_AND_PRIVACY.md` (history now stores encrypted text; dictionary).
- `docs/DEVICE_COMPATIBILITY.md` (Android 13 tablet row).
- `docs/RELEASE_PROCESS.md`, `docs/WIRELESS_ADB.md` (verify currency).
- `docs/ACCESSIBILITY_DESIGN.md` (verify currency).
- `docs/ARCHITECTURE.md` — rewrite to the current module layout, or fold its
  remaining value into the engine docs and remove.
- `docs/GEMINI_LIVE_TRANSCRIPTION.md` — keep as the high-level engine doc; point
  to the new wire reference.

### 11.3 New: `docs/GEMINI_LIVE_WIRE_REFERENCE.md` (the minutia)

Exhaustive reference, verified against the code:

1. **Endpoint & auth** — exact URL:
   `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>`;
   key redaction in every log line (`key=<redacted>`); the key never appears in
   events, candidates, or metrics.
2. **Client → server, every message, exact JSON**:
   - `setup` — full shape: `model: "models/<model>"`,
     `generationConfig.responseModalities: ["AUDIO"]`,
     `inputAudioTranscription: {}` (empty — the API rejects a `languageCode`
     field: "unknown name language code", verbatim error recorded),
     `realtimeInputConfig.automaticActivityDetection.disabled: true`,
     `systemInstruction.parts[].text` per language × polish level.
   - `realtimeInput.activityStart: {}` — push-to-talk start.
   - `realtimeInput.audio` — base64 PCM16, 640 bytes/frame @16 kHz mono,
     `mimeType: "audio/pcm;rate=16000"`.
   - `realtimeInput.activityEnd: {}` — push-to-talk end.
   - Rejected/never-used: `clientContent.turnComplete`, text turns
     (`clientContent.turns`), `realtimeInput.audioStreamEnd` (automatic-VAD
     variant only), `outputAudioTranscription` (off by default).
3. **Server → client, every message, exact JSON + parse mapping**:
   - `setupComplete` → readiness gate (`awaitReady`).
   - `setupError.error.message` / top-level `error.message` → typed
     `gemini_setup`.
   - `serverContent.inputTranscription.text` → **the only dictation source**.
   - `serverContent.outputTranscription.text` → counted for diagnostics, never
     selected.
   - `serverContent.modelTurn.parts[].text` → parsed for compatibility, never
     selected.
   - `serverContent.turnComplete` / `interrupted` → lifecycle flags.
   - `goAway` → `SessionEnd`.
   - **Binary-frame delivery**: every server message arrives as a binary frame
     (opcode 0x2) — why the `onMessage(WebSocket, ByteString)` callback is
     mandatory and must never be removed.
4. **Transport lifecycle** — HTTP 101 upgrade; OkHttp ping interval 20 s / pong
   timeout 20 s; observed close codes (1000 normal, 1008 "The operation was
   aborted"); the 15 s ready timeout; reconnect/backoff policy (warm pool:
   1/2/5/10 s); session `close()` idempotence.
5. **Full session timeline** — stage → metrics mapping (`tapAt` →
   `insertionResultAt`), the wire-order invariant
   (`setup → setupComplete → activityStart → ordered audio → activityEnd`), and
   a worked example `SESSION DONE` line decoded field by field.
6. **Error taxonomy** — every typed failure: `gemini_setup`, `gemini_transport`,
   `gemini_no_transcript`, `gemini_connection_too_slow`, `runtime_no_api_key`,
   `runtime_mic_permission`, `runtime_no_accessibility`, `insert_ambiguous` —
   exact trigger, retryAllowed, user-visible message.
7. **Settlement internals** — accumulator merge rules (extend/duplicate/
   correction/word boundary), selector trust policy + `RejectionRule`
   (`BLANK/PUNCTUATION_ONLY/GARBLED/DEVANAGARI`) + `diagnose()`, the 3 s
   absolute deadline, 250 ms debounce, provisional-fragment rule, lenient
   fallback.
8. **State machines** — transport
   (`Connecting → Ready → ActivityStarted → ActivityEnded → Closed`) and the
   dictation lifecycle (`Idle → Starting → Listening → Finalizing → Inserting →
   Success/Error/Cancelled`), ASCII diagrams.
9. **Module interaction** — sequence diagram: overlay intent → coordinator →
   warm/cold session → wire → events → accumulator → selector → insertion IPC →
   history/metrics logging.

---

## 12. Phase 9 — Integration and versioning (device-free)

1. Version bump `0.4.0` (versionCode 23); `docs/PUSH_TO_DEVICE.md` refresh;
   `CHANGELOG.md` entry.
2. Full gate after every wave: `:app:testDebugUnitTest :app:lintDebug
   :app:assembleDebug` — **no device required**.
3. Final development gate: everything green, APK assembled, docs complete.
   **No on-device testing happens here** — all of it is deferred to Phase 10.
4. Commit **per phase** on `feature`. **No merge to `main` until the user asks.**

---

## 13. Phase 10 — Device validation (deferred; run when the phone is connected)

**Runs only after all development (Phases 1–9) is complete**, when the user
connects the phone/tablet. Everything below uses the on-device protocol
(`docs/ON_DEVICE_TEST_PROTOCOL.md`) and live `SESSION DONE` diagnostics. It is
the single overnight-independent step: build everything, then validate once on
hardware.

1. **Install + Stage 0 preconditions** on both devices (S25 and the Android 13
   tablet): version matches, permissions (incl. the new runtime RECORD_AUDIO /
   POST_NOTIFICATIONS flow), accessibility, FGS notification, both processes up.
2. **Android 13 tablet pass** — confirm the minSdk 33 build installs and runs;
   if the inert `specialUse` FGS bit is rejected, apply the documented
   two-argument `startForeground` fallback and rebuild.
3. **Full device matrix**:

| Scenario | Runs | Expected |
| --- | ---: | --- |
| Stage 0–7 protocol (both devices) | — | all stages pass |
| Movable bubble across keyboards/apps | 10 | drag moves freely, clamped on-screen, position persists |
| Bubble tap still starts dictation | 5 | no accidental drags |
| Capsule UI + circular waveform | 5 | Stop/Cancel work; animation shows |
| Auto-stop: silence (each option) | 5/option | auto-finalizes once |
| Auto-stop: hard cap (each option) | 5/option | auto-finalizes once |
| Polish NONE vs LOW vs MEDIUM vs HIGH | 10 | record per-level output quality |
| Hinglish still Latin at every polish level | 10 | never Devanagari |
| Dictionary corrections applied | 10 | corrected spelling inserted |
| History list/copy/delete/delete-all | 10 | correct entries, retention honored |
| Rapid consecutive sessions | 10 | no stale-candidate errors |
| Retry button on error | 5 | fresh session starts |

4. Record results per device in `docs/DEVICE_COMPATIBILITY.md`; any failures
   become fixes on `feature` (re-run the JVM gate + the affected device cases).
5. Only after this phase passes does the user decide on merging to `main`.

---

## 14. Sub-agent orchestration (waves + file ownership)

Single-owner rule per file per wave to avoid conflicts. Each agent verifies its
own tests; the lead runs the full JVM gate (test + lint + assemble) after each
wave. **No wave requires a connected device.**

| Wave | Agents (parallel) | Owns | Disjoint from |
| --- | --- | --- | --- |
| 1 — Pure/new modules | A: `EncryptedHistoryRepository` + tests · B: `DictionaryCorrections` + tests · C: `BubblePlacement` + tests · D: `CircularWaveform` composable · E: `TranscriptionStyle` + `LanguageMode.liveInstruction(style)` + tests | brand-new files (+ `LanguageMode.kt` alone) | everything |
| 2 — Integration | F: overlay agent (drag host + capsule panels + `onDrag` + bubble position settings keys) · G: coordinator agent (auto-stop jobs + `onSessionFinished` transcript param + tests) · H: settings-repo agent (all new keys: autoStop/polish/dictionary/bubble) | F: `platform/overlay/*` · G: `DictationCoordinator.kt` + tests · H: `data/settings/*` + tests | disjoint |
| 3 — Service + Android 13 | I: `FlowRuntimeService` (FGS SDK guards, history recording, dictionary apply, polish pass-through) + `AndroidManifest.xml` + `build.gradle.kts` · J: `MainActivity` (permissions + Home/Settings/History routing) | I: service/manifest/gradle · J: MainActivity | disjoint |
| 4 — UI | K: `SettingsScreen` (all new sections) · L: `HistoryScreen` (new) | K: SettingsScreen + strings · L: new file + strings | disjoint |
| 5 — Docs | M: `GEMINI_LIVE_WIRE_REFERENCE.md` · N: doc cleanup/update pass | docs/ only | disjoint |
| 6 — Integration | Lead: full build/test/lint, version, APK | verification (device-free) | — |

---

## 15. Testing strategy

- **JVM unit tests** — every pure module (placement, corrections, history repo,
  styles, metrics, coordinator virtual-time tests incl. auto-stop, selector,
  accumulator). No device, no live key. This is the entire automated gate for
  Phases 1–9.
- **Lint** — `warningsAsErrors`; manifest `tools:targetApi` resolves the
  `specialUse` NewApi errors.
- **Device tier** — consolidated into Phase 10 (§13), run only after all
  development is complete, on both the S25 and the Android 13 tablet.
  Accessibility/overlay/FGS behaviors remain physical-device mandatory there.
- **No secrets policy** — the wire reference and all docs stay free of API keys,
  authenticated URLs, transcripts, and audio; `SESSION DONE`/history storage
  rules unchanged (history text is user-opt-in, encrypted).

---

## 16. Risks and open items

1. **FGS on API 33** — the inert `specialUse` bit is expected to be ignored;
   verified empirically on the tablet with the documented two-argument
   `startForeground` fallback.
2. **Instruction-based polish** — styles bias the ASR; HIGH restructuring is
   best-effort. The matrix records actual distinguishability; the toggle remains
   harmless even if levels converge.
3. **Hinglish + polish composition** — the Latin-script rule and the style rule
   must coexist in one instruction; verified on-device at every level.
4. **History text storage** — new privacy surface: encrypted, opt-in,
   retention-capped; documented in `SECURITY_AND_PRIVACY.md`.
5. **History-off semantics** — toggling off stops recording but keeps existing
   entries (Delete All available). Flag if off should wipe.
6. **Auto-stop + long dictation** — the cap will cut off speakers who pause
   long; the 300 s option covers that use case.
7. **Doc removals** — the removal list in §11.1 is proposed; confirm during the
   docs wave before deleting anything tracked (git history preserves it).

---

## 17. Expected end state (0.4.0)

- Installs and works on **Android 13+** (tablet verified) and Android 14–16.
- The mic bubble is **freely draggable** to any screen position, persists, and
  resets from Settings.
- Listening shows a **compact capsule** with an animated circular waveform and
  exactly Stop + Cancel.
- **Auto-stop** (silence + cap) with user-selectable 15/30/60/120/300 s.
- **Four polish levels** (NONE/LOW/MEDIUM/HIGH, default MEDIUM) via the
  systemInstruction, composed with the Hinglish Latin rule.
- **Custom dictionary** correction rules applied at insertion.
- **History** is real: encrypted, viewable, copy/delete/delete-all, retention-
  aware.
- **Docs**: obsolete docs removed; keepers updated; the exhaustive
  `GEMINI_LIVE_WIRE_REFERENCE.md` documents the voice engine to the minutia.
- All work lives on the `feature` branch; merged to `main` only on explicit
  request.
