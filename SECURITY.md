# Security Policy

WhisperType Android is a private, local-first dictation app. Its security model is centered on keeping the Gemini API key encrypted on the device, keeping transcripts and audio out of storage and logs by default, and keeping the Accessibility Service's reach minimal.

## Reporting a vulnerability

This project ships to trusted personal devices only. If you believe a change creates a security or privacy issue:

1. Do not post secrets, transcripts, audio, samples, or device logs that contain user data in a public issue.
2. Describe the issue with only the minimum non-sensitive details: failure codes, device class (for example Pixel or Samsung), Android version, keyboard, and the component involved.
3. Reporting happens privately to the repository maintainers; there is no public bug bounty program.

Reasonable reports are acknowledged and triaged as a regular PR with the `privacy` scope label. The `SourcePrivacyAuditTest` suite prevents regressions of the specific classes described below.

## Supported versions

| Version | Support |
| --- | --- |
| 0.1.x | Supported (current private prototype line; changes land on `main` and are released from there). |

Only the current minor line is actively maintained. Devices running a version older than the latest were tested in the historical line receive security fixes by the current release.

## App threat model summary

- **API key encryption**. The Gemini API key is encrypted with an Android Keystore AES-GCM 256-bit key (alias `whispertype_api_key`) and stored as ciphertext plus a random 12-byte IV in an app-private no-backup file. The plaintext key is never written to DataStore, SharedPreferences, Room, resources, BuildConfig, or logs; it exists only transiently in memory. Incoming keys are rejected unless prefixed `AIza`. If the Keystore reports the key invalidated, the stored ciphertext is discarded and the user is asked to re-enter the key.
- **No key in Git**. Keystore files, `signing.properties`, API keys, and transcripts are blacklisted for the repository (text node). The optional local history is encrypted with a separate AES-GCM Keystore key (`whispertype_history`).
- **Transcripts are not stored in v0.1**. Transcript candidates exist only in memory for the duration of a session and are cleared on insertion, copy fallback, cancellation, or failure. Stored only if the user enables optional local history (disabled by default). Transcripts are never logged.
- **No backend server**. Audio streams directly from the device to the Gemini Live API over TLS. There is no WhisperType cloud account or backend.
- **Backup exclusions.** `android:allowBackup="false"` plus `data_extraction_rules.xml` exclude every backup/device-transfer path (root, database, sharedpref, file, external).
- **Diagnostics redaction.** `DiagnosticsExporter` keeps at most 256 typed events with aggregate timing. Exports never include transcripts, audio, API keys, the authenticated Gemini URL, editor text, or app package data. Logging tags are stable and non-sensitive (`WT-Accessibility`, `WT-Gemini`, `WT-Dictation`, `WT-Settings`), and `SecretRedactor` is applied to logs and exceptions.
- **Accessibility disclosure.** The service requests only editable-field detection, keyboard-window bounds, input connection access, and final text insertion, and never reads or stores unrelated screen content. Secure fields are excluded.
- **Microphone.** Recorded only during an active dictation session initiated by the user. A foreground notification with Stop and Cancel actions is shown while recording; there is no background recording.

## Security-critical code

- `security/SecretStore.kt`, `security/KeystoreManager.kt`, `security/SecretRedactor.kt`, `security/SensitiveClipboard.kt`
- `accessibility/AccessibilityInsertionController.kt`, `accessibility/SecureFieldClassifier.kt`, `accessibility/TargetToken.kt`
- `gemini/GeminiLiveWebSocket.kt` (never logs the authenticated URL or keys)
- `history/EncryptedHistoryStore.kt`, `history/HistoryRepository.kt`
- `app/src/test/java/com/whispertype/android/audit/SourcePrivacyAuditTest.kt` (regression gate)