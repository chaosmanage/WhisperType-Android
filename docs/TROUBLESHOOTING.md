# WhisperType Android — Troubleshooting

Common issues, ordered from the most frequent cause. Diagnostics are privacy-safe: they contain only typed error codes and aggregate timing (see `Settings -> Troubleshooting` and `docs/SECURITY_AND_PRIVACY.md`). WhisperType never logs transcripts, API keys, or the authenticated Gemini URL.

## Accessibility service not binding

The dock and dictation depend on the Accessibility Service.

Check:

1. `Settings -> Accessibility -> Downloaded/Installed apps -> WhisperType` shows the service enabled.
2. The service is not listed as stopped. Android can stop accessibility services under aggressive battery management, especially on Samsung.
3. If it is enabled but the dock never appears, toggle it off and on, then focus a text field.

The service cancels the active session when it disconnects; re-enabling restores normal behavior. The in-app status screen shows whether the service is active.

## The dock does not appear

The dock requires all of the following at once:

1. Accessibility Service enabled.
2. Microphone permission granted.
3. A Gemini API key saved (the key status is shown in Settings).
4. A focused text field that is not secure (password/PIN/payment fields never show the dock).
5. A fully visible, supported docked keyboard (no floating/split layouts).
6. The focused app is not in the "Disable dictation in apps" list (`Settings -> Language and apps`).
7. The screen is unlocked.

If the keyboard was just shown, the dock may take up to ~300 ms to appear while IME bounds settle. Re-focus the field if needed.

## Microphone permission

- Grant from the in-app setup, or `Settings -> Apps -> WhisperType -> Permissions -> Microphone`.
- If the microphone was revoked mid-session, WhisperType cancels the dictation immediately.
- A mic start failure shows a typed error: `MIC_PERMISSION`, `MIC_UNAVAILABLE`, or `MIC_LOST`. If another app is using the microphone, close it first.

## Notification permission

The recording notification is required while the microphone is active. If notifications were denied:

1. `Settings -> Apps -> WhisperType -> Notifications`.
2. Enable notifications.
3. Start a dictation and confirm the "WhisperType is listening" notification appears with Stop and Cancel actions.

Without the notification permission, starting a foreground service may fail with `FGS_START_DENIED` on Android 14+.

## API key validation

- `Settings -> Privacy -> Gemini API key -> Save`, then `Test connection`.
- "Invalid API key": the key does not start with `AIza` (rejected at entry) or Gemini rejects it (400/401/403). Create a new key in Google AI Studio with Gemini API access.
- "Network error": no internet or a VPN/firewall/private DNS is blocking `generativelanguage.googleapis.com`.
- A session started without a stored key fails immediately with `API_KEY_MISSING`.
- If the key was invalidated by the device (Keystore `KEY_INVALIDATED`), the app discards the stored ciphertext and asks you to re-enter the key.

## Network loss / FGS_START_DENIED

- Temporary network loss after audio has started produces `NETWORK_LOST` (recoverable) — no retry happens mid-session; start a new dictation when connectivity returns.
- Timeouts (`TIMEOUT`, `TIMEOUT_WAITING_RESULT`) mean the speech service did not respond within 15 s.
- `FGS_START_DENIED` means Android refused to start the microphone foreground service. Check that the notification permission is granted, another app is not holding the mic, and the app is not in a restricted/stopped state.

## Secure fields never show the dock

Expected behavior. Password, PIN, payment, banking-authentication, private-browsing, and flag-secure fields are excluded by design (see `docs/ACCESSIBILITY_DESIGN.md`). WhisperType never appears there and never records in them.

## Insertion fails (text offered as Copy instead)

Fields without a safe input connection cannot be written to directly: unusual WebViews, canvas-based editors, remote-desktop apps, and some OEM text fields. WhisperType finishes the transcription and shows a Copy fallback instead of silently pasting. Tap `Copy`, return to the field, and paste with your keyboard.

If insertion fails with a non-recoverable code (`PACKAGE_CHANGED`, `DISPLAY_CHANGED`, `FOCUS_CHANGED`, `SECURE_FIELD`, `ALREADY_CONSUMED`), the field state changed during dictation and the result was intentionally discarded — start again.

## OEM battery optimizations

- Samsung: `Settings -> Apps -> WhisperType -> Battery`; choose the least restrictive setting available. If the Accessibility Service keeps stopping, re-enable it from `Settings -> Accessibility -> Installed apps -> WhisperType`.
- Aggressive battery managers may kill the process between dictations; that is benign because WhisperType does no background recording.

## Diagnostics export

`Settings -> Troubleshooting -> Export diagnostics` shares the in-memory diagnostics buffer: typed event codes, durations, and version info. Use it when a problem persists:

1. Reproduce the issue (dock missing, dictation error, insertion failure).
2. Export diagnostics immediately.
3. Share the text with the maintainers.

The export never contains transcripts, audio, API keys, the authenticated Gemini URL, editor text, or app package data. The same buffer feeds crash and timing analysis via `diagnostics/DiagnosticsExporter.kt`.

## Privacy-safe reporting

When reporting an issue: redact the diagnostics text with `security/SecretRedactor` if in doubt, never paste API keys or transcripts, and never attach logcat output that may contain accessibility node content. The `SourcePrivacyAuditTest` and `docs/SECURITY_AND_PRIVACY.md` define the acceptable content.
