# WhisperType Android — Security and Privacy

This document describes how WhisperType Android handles secrets, transcripts, history, the clipboard, logging, Accessibility access, and the microphone. It is the security and privacy reference for the app and is mirrored by the implementation plan's audit checks (Phase 14).

## API key storage

The Gemini API key is the only secret the app stores.

- The key is encrypted with an Android Keystore AES-GCM 256-bit key under the alias `whispertype_api_key`.
- Every encryption uses a random 12-byte IV; no IV is reused.
- The ciphertext and its IV are stored in an app-private no-backup file inside `getNoBackupFilesDir()`.
- The plaintext key is never placed in DataStore, SharedPreferences, Room, resources, BuildConfig, environment variables, or logs.
- Plaintext key material is cleared from memory after each use.
- Incoming keys are rejected if they do not start with the `AIza` prefix (invalid-key rejection at entry).
- If the Keystore reports `KEY_INVALIDATED` (for example after device lock-state or key-access changes), the stored ciphertext is discarded and the recovery path asks the user to re-enter the key.
- Keys are not bound to user authentication; a lock-screen credential is not required to use the key.

## Backup exclusion

- `android:allowBackup="false"` is set in the application manifest.
- Secrets and history live under `getNoBackupFilesDir()`.
- Secret and history files are never backed up to Google or Samsung cloud, and never transferred between devices.

## Transcript handling

- Transcript candidates exist only in memory for the duration of a dictation session.
- Candidate memory is cleared after successful insertion, copy fallback, cancellation, or fatal failure.
- Transcripts are never logged.
- Transcripts are never written to disk unless the user has enabled optional local history.

## Optional history

- Local history is **opt-in** and disabled by default; transcript text is recorded only while it is enabled.
- When enabled, completed valid dictations are stored in an encrypted file store using a Keystore AES-GCM key under the alias `whispertype_history`.
- The history store lives in no-backup storage.
- Retention is capped by a retention-days setting; entries older than the chosen period are pruned automatically.
- History is viewable and deletable in-app (`Settings → Privacy and history → View history`): copy an entry, delete a single entry, or clear all.
- `Clear all history` removes the stored records.
- Audio is never stored.
- API keys are never stored.
- Full editor context is never stored.
- The app package is stored only when the user explicitly enables history metadata.
- When history is disabled, no database or transcript file is created.

## Custom dictionary

- The custom dictionary (correction rules) is stored locally in app-private storage; it is never uploaded, backed up, or sent to Gemini.
- Correction rules are applied at insertion time — they are not prompt injection and are never included in the session `systemInstruction`.
- Dictionary entries are never logged.

## Clipboard behavior

- WhisperType never reads the clipboard.
- The clipboard is written only on an explicit user Copy action.
- Written content is marked sensitive using `ClipDescription.EXTRA_IS_SENSITIVE` where supported.
- After copying, the app shows `Copied — paste with SwiftKey/Gboard` guidance plus a Dismiss action, and clears the in-memory result.

## Logging restrictions

- Logging uses stable, non-sensitive tags only: `WT-Accessibility`, `WT-Gemini`, `WT-Dictation`, `WT-Settings`.
- `SecretRedactor` is applied to all log and exception output.
- The app never logs: API keys, authenticated Gemini URLs, complete transcripts, audio, AccessibilityNode trees, clipboard content, or custom dictionary entries.
- Crash reports and diagnostics carry only typed error codes and aggregate timing metadata.

## Accessibility disclosure

The exact service description shown to the user in Android's Accessibility settings (from `app/src/main/res/values/strings.xml`) is:

> WhisperType detects when a text field is focused and the keyboard is visible so it can place a small microphone control at the keyboard boundary. It inserts the dictated text at the cursor when you tap Stop. It does not read or store unrelated screen content.

The service is limited to:

- editable-field detection,
- keyboard-window bounds,
- input connection access,
- final text insertion.

It does not inspect unrelated screen content.

## Microphone

- `RECORD_AUDIO` is used only during an active dictation session.
- A foreground notification with Stop and Cancel actions is shown while recording.
- Android's privacy indicator (the green dot) is visible while recording.
- There is no continuous background recording.

## Security test checklist

From implementation plan section 8 (Phase 3 — Secure credential storage), the following tests must pass:

- Store and retrieve key.
- Replace key.
- Delete key.
- Corrupted ciphertext.
- Keystore unavailable.
- Device lock state.
- Backup exclusion.
- Log scanning for key patterns.

## Data flow

Tap the dock → audio streams directly from the device to Gemini Live over TLS → the final validated text is inserted at the cursor, or offered as a copy fallback. There is no WhisperType backend server and no cloud account.
