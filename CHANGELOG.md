# Changelog

All notable changes to WhisperType Android are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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