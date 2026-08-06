# WhisperType Android — Troubleshooting

Common issues, ordered from the most frequent cause. WhisperType never logs transcripts, API keys, or the authenticated Gemini URL. The privacy-safe in-app signal for reporting a session is the `SESSION DONE` logcat line (see "Reporting a session problem" below); the acceptable-content rules are in `docs/SECURITY_AND_PRIVACY.md`.

## Accessibility service not binding

The bubble and dictation depend on the Accessibility Service.

Check:

1. `Settings -> Accessibility -> Downloaded/Installed apps -> WhisperType` shows the service enabled.
2. The service is not listed as stopped. Android can stop accessibility services under aggressive battery management, especially on Samsung.
3. If it is enabled but the bubble never appears, toggle it off and on, then focus a text field.

The service cancels the active session when it disconnects; re-enabling restores normal behavior. The Home tab shows an "Accessibility service" status tile — if it reads Off, tap it to jump to the system Accessibility settings.

## The bubble does not appear

The bubble requires all of the following at once:

1. Overlay (draw over other apps) permission granted — the app prompts for it on first launch.
2. The runtime service is running (Home tab -> "Runtime service" tile -> Fix restarts it).
3. Accessibility Service enabled.
4. Microphone permission granted.
5. A Gemini API key saved (key status is shown on the Home tab and under `Settings -> Gemini account`).
6. The app is enabled: `Settings -> General -> App enabled` must be ON. When it is off, the bubble is hidden entirely — this is the kill switch.
7. A focused text field that is not secure (password/PIN/payment fields never show the bubble).
8. A fully visible, supported docked keyboard (no floating/split layouts).
9. The screen is unlocked.

If the keyboard was just shown, the bubble may take up to ~300 ms to appear while IME bounds settle. Re-focus the field if needed.

## Mini-dot / bubble

After a few seconds of idle (1-15 s, default 3 s), the bubble auto-minimizes into a small dot. Tapping the dot starts dictation exactly like tapping the bubble. A visible dot is not a fault — the bubble is just minimized. You can disable this in `Settings -> Bubble -> Mini dot`, or change the delay in `Settings -> Bubble -> Turn to dot after`.

## Microphone permission

- Grant it from the Home tab (tap the "Microphone permission" tile -> Fix) or `Settings -> Apps -> WhisperType -> Permissions -> Microphone`.
- If the microphone is revoked mid-session, WhisperType cancels the dictation immediately.
- A mic start failure shows a typed error: `runtime_mic_permission`, `MIC_INIT`, or `MIC_READ`. If another app is using the microphone, close it first.

## Notification permission

The recording notification is required while the microphone is active. If notifications were denied:

1. `Settings -> Apps -> WhisperType -> Notifications`.
2. Enable notifications.
3. Start a dictation and confirm the "WhisperType is listening" notification appears with Stop and Cancel actions.

Without the notification permission, the recording foreground service cannot show its notification while the microphone is active and dictation fails to start.

## API key validation

- Enter the key under `Settings -> Gemini account` and tap `Save key`. The key is encrypted with Android Keystore-backed storage and never stored in logs, preferences, or backups.
- Gemini rejects an invalid key with 400/401/403 on the live connection. Create a new key in Google AI Studio with Gemini API access.
- "Network error": no internet or a VPN/firewall/private DNS is blocking `generativelanguage.googleapis.com`.
- A session started without a stored key fails immediately with `runtime_no_api_key`.
- If the device invalidates the Keystore key, decryption fails and the app discards the stored ciphertext — re-enter the key.

## Network loss / FGS_START_DENIED

- A transport failure while the session is active surfaces as `gemini_transport`; a session cancelled by network loss reports `NETWORK_FAILURE`. No retry happens mid-session — start a new dictation when connectivity returns.
- Timeouts (`TIMEOUT`) mean the speech service did not respond in time; check your connection and try again.
- `FGS_START_DENIED` means Android refused to start the microphone foreground service. Check that the notification permission is granted, another app is not holding the mic, and the app is not in a restricted/stopped state.

## Secure fields never show the bubble

Expected behavior. Password, PIN, payment, banking-authentication, private-browsing, and flag-secure fields are excluded by design. WhisperType never appears there and never records in them.

## Insertion fails (text offered as Copy instead)

Fields without a safe input connection cannot be written to directly: unusual WebViews, canvas-based editors, remote-desktop apps, and some OEM text fields. WhisperType finishes the transcription and shows a Copy fallback instead of silently pasting. Tap `Copy`, return to the field, and paste with your keyboard.

If the text could not be committed, WhisperType surfaces `insert_ambiguous` (Copy fallback) or fails with `insert_target_stale`, `insert_target_not_safe`, or `insert_connection_unavailable` — the result was intentionally not committed because the field state changed or was protected. Tap the field and start again.

## Why a session almost never loses words

Expected behavior. Reliability comes from the **echo completeness gate**: the
polished echo is accepted only when its content covers the raw ASR, otherwise
the complete raw is salvaged — and a truncated echo never triggers a retry. If
neither live source delivers anything usable, the session reports
`gemini_no_transcript` with a Retry affordance instead of inserting a fragment.
WhisperType uses only the `gemini-3.1-flash-live-preview` live model; there is
no recording re-transcription backstop.

## No transcript could be recognized

Now rare in 0.4.2. When it does happen, check the session's `SESSION DONE` log line:

- `reject=<rule>` — a transcript arrived but the selector rejected it; `lenient=true` means the failsafe still inserted it. Any remaining rejection is blank / punctuation-only / garbled / Devanagari-in-English.
- `inputTx=0` — the Live model returned nothing within the deadline; retry the dictation.
- `settle=<path>` — how the final text was chosen: `echo_complete`, `raw_only`, `echo_partial_raw`, `echo_only`, or `none`.

## App won't install on Android 13

Android 13 is supported since 0.4.0 (minSdk 33). If the install is blocked:

1. Confirm you are installing the 0.4.0 (or newer) build, not an older APK.
2. Allow the app used to open the APK to install unknown apps.
3. On a tablet, confirm the build targets Android 13+ — the first-install flow prompts for overlay permission, then microphone and notifications.

## Permissions

Both `RECORD_AUDIO` and `POST_NOTIFICATIONS` must be granted for dictation to start.

- `Settings -> Apps -> WhisperType -> Permissions`, or tap the matching tiles on the Home tab and use Fix.
- If either was denied, WhisperType cannot start the microphone foreground service (see `runtime_mic_permission` and the notification section above).

## History empty

Local history is on by default (30-day retention) and can be turned off in the History tab; transcript text is recorded only while it is enabled.

1. Open the History tab (bottom navigation).
2. Enable `Save dictation history`.
3. Dictate again — new entries appear in the list on the same page.
4. Note that enabling history does not backfill older sessions, and entries are pruned once older than the retention period (7-90 days). The Home tab stats grid is derived from this history.

## Dictionary not applied

Custom dictionary corrections match word-boundary and are case-insensitive. The dictionary is managed on its own page (Dictionary tab in the bottom navigation).

- Check the word is entered exactly as you will say it (partial/fused matches do not apply).
- Check an `Always write as` spelling is set where needed.
- Corrections are applied at insertion; the live transcript is not rewritten mid-session.

## OEM battery optimizations

- Samsung: `Settings -> Apps -> WhisperType -> Battery`; choose the least restrictive setting available. If the Accessibility Service keeps stopping, re-enable it from `Settings -> Accessibility -> Installed apps -> WhisperType`.
- Aggressive battery managers may kill the process between dictations; that is benign because WhisperType does no background recording.

## Reporting a session problem (SESSION DONE)

The relevant in-app signal is the `SESSION DONE` logcat line, logged once per session by `FlowRuntimeService` when the session finishes. It is privacy-safe: only the outcome class, aggregate counts, stage timings, and flags — never transcripts, audio, API keys, editor text, or the authenticated Gemini URL.

1. Reproduce the issue (bubble missing, dictation error, insertion failure).
2. Capture the line:

   `adb logcat -s FlowRuntimeService` (or filter for `SESSION DONE`).

3. Share the `SESSION DONE` line with the maintainers.

Useful fields: `outcome=`, `inputTx=`, `outputTx=`, `reject=`, `lenient=`, `settle=`.

## Privacy-safe reporting

When reporting an issue: share only the `SESSION DONE` line and other non-sensitive logcat, never paste API keys or transcripts, and never attach logcat output that may contain accessibility node content. The `core/privacy/LogRedactor` strips secrets from app logs. `docs/SECURITY_AND_PRIVACY.md` defines the acceptable content.
