# WhisperType Testing

## Overview

Testing has two automated tiers plus a mandatory manual tier:

1. **JVM unit tests** (`app/src/test`) — fast, device-free; run with `:app:testDebugUnitTest`.
2. **Instrumented tests** (`app/src/androidTest`) — run on a real Android device with `:app:connectedDebugAndroidTest`; split into emulator-safe host tests and physical-device tests that are skipped via `Assume` when the WhisperType Accessibility Service is not enabled.
3. **Manual device acceptance** — required for accessibility/overlay/insertion behavior; see [Physical-device requirement](#physical-device-requirement) below.

## Unit test inventory by package

| Package | Coverage |
| --- | --- |
| `com.whispertype.android.platform.gemini` | `GeminiLiveWireTest` (exact wire codec: setup fields, realtime activity builders, server parse), `OkHttpGeminiLiveSessionTest` (MockWebServer WebSocket: setup-first ordering, single activity start/end, audio rejection before start/after end, no `clientContent`, output transcription never a candidate, automatic-VAD variant, setup errors, close idempotence), `WarmLiveSessionManagerTest` (prewarm/claim/backoff/idle). |
| `com.whispertype.android.platform.runtime` | `DictationCoordinatorTest` — virtual-time orchestration races (duplicate START, cancel during setup, STOP immediately, stale insertion responses, capture failure, rejected boundaries, exactly-once insertion), Release E settlement (deadline/debounce, provisional rejection, retained early turn-complete), Release F pre-ready buffering (order drain, overflow -> `connection_too_slow`), failsafes (lenient fallback insert, retry, persistent errors), and 0.4.0 auto-stop (silence threshold + hard cap, per option). |
| `com.whispertype.android.core.transcript` | `TranscriptSelectorTest` (user-speech trust policy, `diagnose()`, long-sentence regression), `TranscriptAccumulatorTest` (cumulative merge rules). |
| `com.whispertype.android.core.model` | `MutableSessionMetricsTest` (monotonic timing/counters/summary), `LanguageModeTest` (Hinglish instruction; 4 polish styles × languages). |
| `com.whispertype.android.audio` | `AudioCaptureOrderlyShutdownTest` (producer-owned flush, zero-padded partial frame, unblocking a blocking read, timeout fallback), `PreReadyAudioBufferTest` (bounded FIFO, overflow, close). |
| `com.whispertype.android.core.audio` | `BoundedAudioQueueTest`, `Pcm16FrameAssemblerTest`, `AmplitudeMeterTest`. |
| `com.whispertype.android.core.state` | `DictationReducerTest` — state transitions, stale-session rejection, exactly-once consumption. |
| `com.whispertype.android.core.privacy` | `LogRedactorTest`. |
| `com.whispertype.android.data.secrets` | `SecretCipherTest`, `ClientInvalidRecoveryTest` — Keystore/AES-GCM storage, corrupt-blob recovery. |
| `com.whispertype.android.data.settings` | `SettingsRepositoryTest` — defaults and persistence. |
| `com.whispertype.android.core.dictionary` | `DictionaryCorrectionsTest` — word-boundary, case-insensitive correction rules applied at insertion. |
| `com.whispertype.android.core.overlay` | `BubblePlacementTest` — free-drag bounds, persisted bubble position, reset. |
| `com.whispertype.android.data.history` | `EncryptedHistoryRepositoryTest` — opt-in, Keystore AES-GCM, retention-days pruning, delete / delete-all, view/copy. |
| `com.whispertype.android.platform.overlay` | `OverlayHostStateMachineTest`, `OverlayPlacementTest`, `OverlayVisibilityTest`. |
| `com.whispertype.android.platform.accessibility` | `FocusedEditorTest`, `EligibilityMapperTest`, `EligibilityExplanationTest`, `SecurityClassifierTest`, `InsertionDecisionTest`, `InsertionVerifierTest`. |

All unit tests run on the JVM with no device and no live API key.

## Fake WebSocket contract tests

`OkHttpGeminiLiveSessionTest` drives `OkHttpGeminiLiveSession` against a local
MockWebServer WebSocket that mirrors the Live endpoint's **binary-frame**
delivery. It asserts: setup is the first client message with the exact fields
(manual activity config, no output transcription, no `clientContent`), exactly
one `activityStart` / `activityEnd`, ordered audio with `audio/pcm;rate=16000`,
audio rejection before start and after end, prompt failure propagation, and that
`outputTranscription` / `modelTurn` text never emit a user candidate. No live
network or API key is used. See `docs/GEMINI_LIVE_TRANSCRIPTION.md` §4 for the
wire protocol.

## Instrumented host app and scenarios

`TestHostActivity` (`app/src/androidTest/java/com/whispertype/android/test/TestHostActivity.kt`) is a plain Activity with programmatic UI inside a `LinearLayout` within a `ScrollView`. Every element carries a `setTag` for identification:

- Fields: `field_single` (single-line text), `field_multiline` (multi-line text), `field_password` (text password), `field_pin` (numeric password), `field_numeric` (number), `field_selection` (pre-selected range 3..7), `field_web` (WebView with a contenteditable div).
- Controls: `btn_focus_single`, `btn_focus_password`, `btn_focus_multiline`, `btn_hide_keyboard`, `btn_rotate` (portrait ↔ landscape), `btn_open_settings` (app-switch simulation).
- `status_text` reports only the focused field tag (`focused:single`, `focused:password`, `focused:none`); text content is never logged.

Scenarios:

- `TestHostActivityTest` (emulator-safe): all fields present; focus switching updates focus and status text; recreation after rotation keeps the field inventory focusable; the hide-keyboard control completes without crashing.
- `DockEligibilityTest` (physical device): the dock appears for a single-line field with a docking default IME; hiding the keyboard drops the state and the dock never returns; `begin` returns a Boolean without throwing when mic/key prerequisites are missing.
- `SecureFieldTest` (physical device): password and PIN fields never reach `DockedReady`; a numeric field may (unsupported OEM keyboard layouts are skipped via `Assume`, not failed).
- `StaleSessionTest` (physical device): after `begin` returns true, switching focus must cancel the session before any `Success`; if `begin` returns false (no mic/key), the test is skipped.
- `RotationTest` (physical device): after rotation the state settles on `DockedReady` or `NoEditableFocus` — rotation may briefly drop focus, so both states are accepted.

## Device matrix

See `docs/DEVICE_COMPATIBILITY.md` for the phone/keyboard/navigation matrix, the ADB baseline commands, and how to record results.

The 0.4.0 device tier (Android 13+ tablets, runtime permission flow) is deferred to Phase 10 of `docs/IMPLEMENTATION_PLAN_3.md`.

## Manual device acceptance sequence

Run this sequence on every supported phone/keyboard combination before declaring it compatible (Implementation Plan §22.9):

1. Install or upgrade the current debug/release candidate.
2. Open WhisperType and verify permissions and Accessibility status.
3. Confirm the intended third-party keyboard is still the default IME.
4. Focus a normal single-line text field and verify the dock appears.
5. Start dictation and verify the keyboard-covering voice panel.
6. Speak an English test phrase and verify exactly one insertion.
7. Speak a Hinglish test phrase and verify Latin-script output.
8. Replace a selected text range.
9. Cancel a dictation and verify nothing is inserted.
10. Change focus during finalization and verify stale text is not inserted.
11. Test a password/PIN field and verify the dock never appears.
12. Rotate during idle and recording states.
13. Switch apps during recording and verify safe cancellation or the documented behavior.
14. Lock and unlock the phone during recording.
15. Test gesture navigation and three-button navigation where available.
16. Test notification and microphone permission denial/recovery.
17. Test invalid API key and temporary network loss.
18. Run 20 consecutive start/stop dictations and check for service, audio, overlay, or insertion leaks.
19. Reboot the phone and confirm the documented post-reboot behavior.
20. Record pass/fail, build version, device model, Android version, keyboard version, and notes in `docs/DEVICE_COMPATIBILITY.md`.

## Release checklist

Before each private release (Implementation Plan §22.4):

- All unit tests pass.
- All instrumentation tests pass.
- Pixel manual matrix passes.
- Samsung manual matrix passes.
- No secrets scan matches.
- No forbidden logging matches.
- Accessibility disclosure is current.
- Version name and version code are updated.
- Changelog entry exists.
- Release APK is signed.
- SHA-256 checksum is recorded.
- Installation and upgrade are tested.
- User setup guide matches the current UI.
- Known issues are documented.

The "no secrets scan" and "no forbidden logging" items map directly to `SourcePrivacyAuditTest` (rules a–d).

## Physical-device requirement

Physical-device validation is mandatory for Accessibility Service behavior, third-party keyboard geometry, overlay layering, microphone foreground-service startup, text insertion, OEM behavior, and release installation. Emulators are insufficient for these paths (Implementation Plan §22.5). The dock/secure/stale/rotation instrumented tests therefore `Assume`-skip on emulators and on devices without the WhisperType Accessibility Service enabled; a green CI run on emulators alone is never sufficient evidence of compatibility.

## Running the tests

```powershell
./gradlew :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

- `:app:testDebugUnitTest` and `:app:lintDebug` require no device.
- `:app:connectedDebugAndroidTest` requires a connected device; the physical-device tests skip via `Assume` when the accessibility service is not enabled, and additionally skip when the default IME does not support docked mode.
