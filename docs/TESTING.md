# WhisperType Testing

## Overview

Testing has two automated tiers plus a mandatory manual tier:

1. **JVM unit tests** (`app/src/test`) — fast, device-free; run with `:app:testDebugUnitTest`.
2. **Instrumented tests** (`app/src/androidTest`) — run on a real Android device with `:app:connectedDebugAndroidTest`; split into emulator-safe host tests and physical-device tests that are skipped via `Assume` when the WhisperType Accessibility Service is not enabled.
3. **Manual device acceptance** — required for accessibility/overlay/insertion behavior; see [Physical-device requirement](#physical-device-requirement) below.

## Unit test inventory by package

| Package | Coverage |
| --- | --- |
| `com.whispertype.android.audio` | Audio queue overflow, queue drain before activity end, waveform helpers. |
| `com.whispertype.android.dictation` | State reducer transitions, stale-session rejection, exactly-once result consumption, target-token comparison, secure-field classification, timeout and cancellation. |
| `com.whispertype.android.gemini` | Live protocol (setup ordering, audio message format, single activity-end boundary, raw and cleaned transcript events, turn completion, cleaned-candidate fallback, protocol error, timeout, cancellation, network loss, no retry after audio, no key or authenticated URL in logs), transcript assembler, fake-WebSocket contract tests. |
| `com.whispertype.android.validation` | Transcript corruption rules, Hinglish Latin-only validation, cleaned/raw fallback, candidate selection. |
| `com.whispertype.android.security` | Encrypted secret storage, sensitive clipboard behavior, secret redaction. |
| `com.whispertype.android.history` | History retention policy. |
| `com.whispertype.android.settings` | Settings defaults and persistence. |
| `com.whispertype.android.overlay` | Dock placement calculation, keyboard geometry validation, overlay safe-inset calculation. |
| `com.whispertype.android.audit` | `SourcePrivacyAuditTest` — on-disk scan for API key literals, authenticated URL literals, sensitive log lines, and committed keystore/secret files. |

Today the `gemini` and `validation` packages are present; the remaining packages land with their feature workstreams (Implementation Plan §18.1). No CI test may require a live Gemini API key (§18.2).

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

## Fake WebSocket contract tests

`GeminiLiveClientContractTest` drives `DefaultGeminiLiveClient` against a local `MockWebServer` fake WebSocket. It asserts setup ordering, audio message framing, the single activity-end boundary, raw and cleaned transcript events, turn completion, candidate fallback, protocol error/timeout/cancellation/network-loss handling, and that no API key or authenticated URL appears in emitted logs. No live network or API key is used. See `docs/GEMINI_LIVE_PROTOCOL.md` for the wire protocol.

## Device matrix

See `docs/DEVICE_COMPATIBILITY.md` for the phone/keyboard/navigation matrix, the ADB baseline commands, and how to record results.

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
