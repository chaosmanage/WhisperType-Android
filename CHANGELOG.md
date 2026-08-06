# Changelog

All notable changes to WhisperType Android are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.1] - 2026-08-06 (in development, feature branch)

Follow-up to 0.4.0 from on-device testing + host probes. See `docs/GEMINI_LIVE_TRANSCRIPTION.md` §9.

- **Echo transcription engine** — the dictation source is now the model's instructed spoken reply (`outputTranscription`): the `systemInstruction` tells the model to repeat the user's speech back verbatim with the selected polish level (and, for Hinglish, in Roman/Latin script). Host probes proved `outputTranscription` works even with a `systemInstruction`, controls Latin script and polish, and streams in ~0.6 s (sentence) to ~8 s (100 words). `inputTranscription` (raw ASR) is the 2 s fast fallback; settlement prefers the echo (always waits for it, 20 s cap).
- **Bubble simplified** — one `TYPE_APPLICATION_OVERLAY` window, always `TOP|START` with an absolute pixel position and real-measured-size clamp; drag shows an X drop-target that hides the bubble until the next eligible field. Removed the gravity-switching placement logic that caused the jump-to-corner bug.
- **Long-dictation insertion fix** — surrounding-text verification window enlarged (5000/500) and a truncation-robust tail match, fixing "Could not confirm the text was inserted / Use Copy" on long dictations.
- **Docs** — `GEMINI_LIVE_TRANSCRIPTION.md` §9 and `GEMINI_LIVE_WIRE_REFERENCE.md` §2.1/§3/§7.3 record the verified echo mechanics.

## [0.4.0] - 2026-08-06 (in development, feature branch)

"Experience & Reach" — see `docs/IMPLEMENTATION_PLAN_3.md`.

- **Android 13+ support** — `minSdk 33` so the Android 13 tablet can install the app.
- **Draggable bubble** — the mic bubble is now freely draggable to any position and the position is remembered.
- **Capsule UI + circular waveform** — compact recording capsule (Stop + Cancel) with an animated circular waveform, replacing the old full-width voice panel.
- **Auto-stop timeout** — silence-based auto-stop and a hard recording cap (whichever fires first); user-selectable 15/30/60/120/300 s, default 60 s.
- **Output-polish levels** — four transcription styles (`NONE`/`LOW`/`MEDIUM`/`HIGH`, default `MEDIUM`) passed through the Gemini `systemInstruction`.
- **Custom dictionary** — client-side correction rules with optional "always write as" spellings, applied at insertion.
- **Encrypted history screen** — viewable, encrypted transcript history: list, copy, delete one, delete all, retention-days respected.
- **Docs overhaul** — removed obsolete/historical docs and rewrote `docs/ARCHITECTURE.md` for the current two-process layout; see also `docs/GEMINI_LIVE_WIRE_REFERENCE.md`.

## [0.3.1] - 2026-08-06

Voice-to-text reliability and Hinglish. See `docs/GEMINI_LIVE_TRANSCRIPTION.md`.

### Transcription (inputTranscription is user speech)

- `TranscriptSelector` now uses a **user-speech trust policy**: only blank,
  punctuation-only, garbled, and Devanagari-in-English are rejected. Removed the
  model-preamble / greeting / acknowledgment / repetition rejections that were
  discarding real speech ("Okay so…", "Yes…", "Of course…", long sentences).
- Added `TranscriptSelector.diagnose()` → rejection-rule logging (never text).
- **Hinglish** now reaches the model via a Hinglish `systemInstruction` that
  biases transcription to **Latin script** (romanized Hindi), never Devanagari.
  A `languageCode` on `inputAudioTranscription` was tried and rejected by the
  Live API ("unknown name language code"), so none is sent.

### Failsafes / retries

- Lenient fallback: a rejected transcript is still inserted unless blank,
  garbled, or a clearly provisional fragment at the hard deadline.
- Retryable errors persist until the user taps **Retry** or **Dismiss**
  (`OverlayIntent.RETRY`, coordinator `retry()` / `dismiss()`); Retry button on
  the error panel.
- `SESSION DONE` now logs `reject=<rule>` and `lenient=true`.

### Tests

- TranscriptSelector reworked + new `diagnose()` coverage; coordinator tests for
  lenient fallback, retry, persistent errors; wire test for the no-languageCode
  setup; new `LanguageModeTest`.

## [0.3.0] - 2026-08-05

- Consolidated the A–F remediation into one release (`docs/GEMINI_LIVE_TRANSCRIPTION.md`).
- Home screen shows `Version <name> (<code>) · commit <git hash>` (BuildConfig
  GIT_COMMIT embedded at build time).

## [0.2.12] – [0.2.18] - 2026-08-05 (remediation releases A–F)

- **A (0.2.12)** — monotonic session metrics/counters + experimental realtime
  wire builders (activityStart / activityEnd / audioStreamEnd).
- **B (0.2.13)** — removed the dictation text prime; `inputTranscription` is the
  only dictation source; manual activity signaling; transport state machine
  `Connecting→Ready→ActivityStarted→ActivityEnded→Closed`; fail promptly on
  rejected boundaries.
- **C (0.2.14)** — host-testable `DictationCoordinator` + per-session holder;
  duplicate-START rejection; session-identity validation; insertion-result
  correlation; producer-owned `Chunker` orderly shutdown; capture-failure
  teardown; race tests.
- **D (0.2.15)** — key/`AudioRecord`/base64/JSON work off main; cached settings
  snapshot; shared `OkHttpClient`; amplitude throttled; removed raw-frame logs.
- **E (0.2.16)** — `TranscriptAccumulator` merge rules; one absolute 3 s
  deadline + 250 ms settle debounce; provisional-fragment rejection; retained
  early `turnComplete`; virtual-time tests.
- **F (0.2.17)** — `WarmLiveSessionManager` prewarm pool (30 s idle, 1/2/5/10 s
  backoff); immediate capture with a bounded 150-frame pre-ready buffer;
  `connecting` UI state.
- **(0.2.18)** — per-session `SESSION DONE outcome=… <metrics>` diagnostics.

## [0.1.0] - Initial private prototype (unreleased)

The foundation build of WhisperType Android. Requires Android 14+ (minSdk 34) and targets Android 16 (API 36).

### Accessibility

- `WhisperTypeAccessibilityService` detects focused editable fields and the on-screen IME window.
- `InputTargetTracker` snapshots the focused editor (package, window, field id, inferred input type) and increments an input generation on every focus change.
- `InputMethodWindowTracker` reports validated, debounced IME bounds (bottom-attached, keyboard-shaped) for dock placement.
- `SecureFieldClassifier` excludes password/PIN input types and flag-secure windows from ever showing the dock.
- Cancels the active session on focus change, IME hide, screen off, or mic-permission revocation.

### Overlay

- `OverlayWindowController` renders a docked circular mic button and a keyboard-covering voice panel as `TYPE_ACCESSIBILITY_OVERLAY` windows (non-focusable, touch-transparent outside bounds).
- `OverlayGeometryCalculator` places the dock by position, size, overlap mode, and opacity, clamped to the display.
- Docked mic UI honors system/light/dark theme, accent color, haptics, elapsed-time visibility, and cancel-side preference.

### Dictation

- `DictationCoordinator` runs exactly one dictation session at a time through a typed state machine (`Starting`, `Listening`, `Finalizing`, `Inserting`, `Success`, `CopyAvailable`, `Error`, `Cancelled`).
- 20 ms PCM16 mono capture via `AudioCapture`, bounded `AudioQueue`, and waveform amplitude for the panel.
- `DictationForegroundService` keeps the session alive with a microphone-type foreground service and a Stop/Cancel notification.
- Finalization drains the audio queue, sends a single activity-end boundary, and auto-dismisses the succeeded state after 900 ms.
- Cancellation discards the result; insertion is guarded by stale-target and secure-field checks.

### Gemini Live

- `GeminiLiveClient` streams audio to Gemini Live over a single WebSocket per session (`gemini-2.0-flash-live-001`, 16 kHz PCM16 mono).
- Raw and cleaned transcript candidates assembled by `GeminiTranscriptAssembler`.
- Typed, non-sensitive failure mapping (`AUTH_ERROR`, `NETWORK_LOST`, `TIMEOUT`, `PROTOCOL_ERROR`, `SERVER_ERROR`, `INTERRUPTED`); no retry after audio transmission.

### Validation and quality chain

- `CleanTranscriptValidator`/`TranscriptValidator` corruption rules (empty/symbol-only/preamble/repeated runs/implausible length).
- `CleanedTranscriptValidator` enforces cleaned <= 3x raw and rejects asterisk-marked output.
- `HinglishValidator` enforces Latin-script-only output in both English and Hinglish modes (Devanagari and other non-Latin scripts rejected).
- `CandidateSelector` prefers cleaned over raw, inserts exactly one candidate or the explicit Copy fallback.

### Security and privacy

- Gemini API key encrypted at rest with an Android Keystore AES-GCM key and stored in no-backup storage; rejected if not prefixed `AIza`.
- `SecretRedactor` applied to all log and exception output; no keys, transcripts, or authenticated URLs logged.
- `SensitiveClipboard` writes are explicit-only and marked `EXTRA_IS_SENSITIVE`.
- Backups fully excluded (`allowBackup=false` + `data_extraction_rules.xml`).

### Settings and onboarding

- DataStore-backed settings: speech mode (English/Hinglish), dock appearance, disabled-apps list, optional encrypted local history (off by default), onboarding completion flag.
- Step-by-step onboarding: intro, microphone, notifications, accessibility, API key, language, dock preview, demo.
- Compatibility test screen against a sample text field.

### Privacy fix (plan §16)

- History now stores the originating app package only when the user explicitly opts in via a new "Record originating app" toggle in `Privacy` settings, off by default. The application-scoped `HistoryRepository` is wired to this opt-in instead of hardcoding metadata inclusion.
- Added `scripts/verify-secrets.ps1` for the plan-required secrets audit (exits non-zero on real Gemini keys or forbidden signing/manifest files).
- Hardened `.gitignore` with emulator snapshot, transcript-fixture, and general Gemini-key patterns.

### Diagnostics

- `DiagnosticsExporter` in-memory 256-event ring buffer of typed codes and timing; export is privacy-safe.

### Known limitations

- Single utterance session per dictation.
- English and Latin-script Hinglish only.
- No transcript history browsing UI in v0.1 (history can be enabled and cleared in Settings).
- Physical-device acceptance still pending in `docs/DEVICE_COMPATIBILITY.md`.