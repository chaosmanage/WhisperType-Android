# WhisperType Android

WhisperType is a private dictation tool for Android that keeps your existing keyboard (SwiftKey, Gboard, or Samsung Keyboard) active. When you focus a normal text field, a small WhisperType microphone control docks at the keyboard boundary. Tap it, speak, and the transcription is inserted at the cursor. Audio streams directly to Google's Gemini Live API over TLS; there is no WhisperType backend server, cloud account, or transcript storage by default.

## How it works

```
+---------------------------+
| App (Messages, Gmail...)  |
|                           |
| [text field      cursor]  |
+---------------------------+
|   [mic]  <-- WhisperType  |
|   dock at keyboard edge   |
|                           |
|  QWERTYUIOP ...           |  normal keyboard stays selected
+---------------------------+
```

Tap the dock -> the keyboard area is covered by the WhisperType voice panel -> speak -> tap Stop -> the validated transcript is inserted at the cursor (or offered as a Copy fallback) -> your normal keyboard returns.

No screenshot is provided in this repository; see `docs/USER_SETUP.md` for the full step-by-step setup and usage guide.

## Supported Android versions

- Android 14 (API 34) through Android 16 (API 36).
- The app targets `compileSdk 36`, `targetSdk 36`, and `minSdk 34`.

## Supported keyboards

- SwiftKey
- Gboard
- Samsung Keyboard
- Any IME that can be the device default (a `LAUNCHER`-category IME) in standard docked mode.

Floating and split keyboard layouts are not supported and will not show the dock.

## Known limitations (v0.1)

- Requires the Accessibility Service to be enabled; WhisperType works only while it is active.
- Secure fields (password, PIN, payment, flag-secure windows) never show the dock; they are excluded by design.
- One dictation session at a time; each session is a single utterance (a 60-second recording is the intended ceiling; the hard limit is 5 minutes).
- English and Latin-script Hinglish. Hinglish instructs the Live model to write Hindi words in Latin script; the app never errors on Devanagari (English mode still rejects it).
- Insertion depends on a safe input connection; fields without one fall back to an explicit Copy action.
- Optional local history can be enabled in Settings (encrypted, off by default), but there is no transcript browsing UI in v0.1.
- Accessibility Node content, transcripts, and audio are never logged or stored by default.

## Gemini Live transcription engine

The voice-to-text pipeline (wire protocol, prompt, state machines, settlement,
and failsafes) is documented in `docs/GEMINI_LIVE_TRANSCRIPTION.md`. The
on-device test procedure is in `docs/ON_DEVICE_TEST_PROTOCOL.md`.

## Build prerequisites

- JDK 17 (the project compiles with `jvmTarget 17`).
- Android SDK with platform 36 installed and its licenses accepted.
- Gradle 8.14.3 (the included wrapper downloads it automatically).
- AGP 8.13.2, Kotlin 2.4.10 (declared in `gradle/libs.versions.toml`).
- Internet access for the Gemini Live API at runtime.

Accept Android SDK licenses if prompted:

```powershell
.\sdkmanager --licenses
```

## Debug build

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It installs with the same application id (`com.whispertype.android`) as the release build.

## Release build

A release build requires a signing keystore. The release signing configuration is read from `signing.properties` at the repository root; without that file the release build is unsigned and fails signature verification. Create the file exactly as documented in `docs/RELEASE_PROCESS.md` (the keystore itself must stay outside the repository):

```text
storeFile=C:/path/to/whispertype-release.keystore
storePassword=...
keyAlias=whispertype
keyPassword=...
```

Then:

```powershell
.\gradlew.bat :app:assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Install

### Side-load on the phone

1. Copy the signed APK to the phone.
2. Open the APK; if Android blocks it, allow the file manager to install unknown apps.
3. Follow the in-app setup (permissions, Accessibility, API key) documented in `docs/USER_SETUP.md`.

### Install over ADB

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Uninstall

1. Open Android `Settings -> Apps -> WhisperType`.
2. Tap `Uninstall` and confirm.

Optionally disable the Accessibility Service, delete the Gemini API key, clear local history, and revoke microphone/notification permissions first.

## Documentation

- `docs/USER_SETUP.md` — permissions, Accessibility setup, API key, daily use, troubleshooting.
- `docs/ARCHITECTURE.md` — component ownership, service boundaries, state machine, insertion safety.
- `docs/ACCESSIBILITY_DESIGN.md` — why Accessibility is required and how the service is constrained.
- `docs/GEMINI_LIVE_PROTOCOL.md` — the Gemini Live wire protocol and quality chain.
- `docs/SECURITY_AND_PRIVACY.md` — secrets, backups, transcripts, logging restrictions.
- `docs/RELEASE_PROCESS.md` — versioning, signing, APK generation, checksums, upgrades.
- `docs/TESTING.md` — test tiers, device matrix, manual acceptance, release checklist.
- `docs/DEVICE_COMPATIBILITY.md` — per-device validation matrix.
- `docs/TROUBLESHOOTING.md` — common issues and diagnostics export.
- `CHANGELOG.md` — release history.
- `SECURITY.md` — security policy and vulnerability reporting.
