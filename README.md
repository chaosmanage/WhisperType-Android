# WhisperType Android

WhisperType is a private dictation tool for Android that keeps your existing keyboard (SwiftKey, Gboard, or Samsung Keyboard) active. When you focus a normal text field, a freely draggable WhisperType microphone bubble appears. Tap it, speak, and the transcription is inserted at the cursor. Audio streams directly to Google's Gemini Live API over TLS; there is no WhisperType backend server, cloud account, or transcript storage by default.

## How it works

```
+---------------------------+
| App (Messages, Gmail...)  |
|                           |
| [text field      cursor]  |
|                           |
|       ( [mic] )  <- freely draggable bubble
+---------------------------+
|                           |
|  QWERTYUIOP ...           |  normal keyboard stays selected
+---------------------------+
```

Tap the bubble -> a compact capsule recording UI (Stop + Cancel) with an animated circular waveform appears -> speak -> the validated transcript is inserted at the cursor (or offered as a Copy fallback) -> your normal keyboard returns.

No screenshot is provided in this repository; see `docs/USER_SETUP.md` for the full step-by-step setup and usage guide.

## Features

- **Android 13+** — runs on `minSdk 33` and above (the Android 13 tablet is a supported target).
- **Freely draggable mic bubble** — touch and drag the bubble to any position on screen; the position is remembered.
- **Capsule recording UI** — a compact capsule with Stop and Cancel plus an animated circular waveform, replacing the old full-width voice panel.
- **Auto-stop timeout** — stops on silence or at a hard recording cap, whichever fires first; user-selectable 15/30/60/120/300 s (default 60 s).
- **Four output-polish levels** — `NONE` (raw) / `LOW` / `MEDIUM` / `HIGH` (default `MEDIUM`), passed to the model through the Gemini `systemInstruction`.
- **Custom dictionary correction rules** — add words with an optional "always write as" spelling; corrections are applied client-side at insertion.
- **Encrypted, viewable History screen** — list, copy, delete-one, and delete-all over the encrypted transcript store.

## Supported Android versions

- Android 13 (API 33) through Android 16 (API 36).
- The app targets `compileSdk 36`, `targetSdk 36`, and `minSdk 33`.

## Supported keyboards

- SwiftKey
- Gboard
- Samsung Keyboard
- Any IME that can be the device default (a `LAUNCHER`-category IME) in standard docked mode.

Floating and split keyboard layouts are not supported and will not show the bubble.

## Known limitations (v0.4)

- Requires the Accessibility Service to be enabled; WhisperType works only while it is active.
- Secure fields (password, PIN, payment, flag-secure windows) never show the bubble; they are excluded by design.
- One dictation session at a time; each session is a single utterance bounded by the configured auto-stop (silence + hard cap).
- English and Hinglish. Hinglish instructs the Live model to write Hindi words in Latin script; the app is defensive about Devanagari in Hinglish mode (English mode still rejects it).
- Insertion depends on a safe input connection; fields without one fall back to an explicit Copy action.
- Optional local history can be enabled in Settings (encrypted, off by default); the viewable History screen appears once enabled.
- Accessibility Node content, transcripts, and audio are never logged or stored by default.

## Gemini Live transcription engine

The voice-to-text pipeline (wire protocol, prompt, state machines, settlement,
and failsafes) is documented in `docs/GEMINI_LIVE_TRANSCRIPTION.md`. The
exhaustive wire mechanics reference is `docs/GEMINI_LIVE_WIRE_REFERENCE.md`.
The on-device test procedure is in `docs/ON_DEVICE_TEST_PROTOCOL.md`.

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
- `docs/ARCHITECTURE.md` — current architecture: the two processes, module map, session flow.
- `docs/GEMINI_LIVE_TRANSCRIPTION.md` — the Gemini Live voice transcription engine.
- `docs/GEMINI_LIVE_WIRE_REFERENCE.md` — exhaustive Gemini Live wire mechanics reference.
- `docs/IMPLEMENTATION_PLAN_3.md` — the 0.4.0 evolution plan.
- `docs/SECURITY_AND_PRIVACY.md` — secrets, backups, transcripts, logging restrictions.
- `docs/RELEASE_PROCESS.md` — versioning, signing, APK generation, checksums, upgrades.
- `docs/TESTING.md` — test tiers, device matrix, manual acceptance, release checklist.
- `docs/DEVICE_COMPATIBILITY.md` — per-device validation matrix.
- `docs/TROUBLESHOOTING.md` — common issues and diagnostics export.
- `CHANGELOG.md` — release history.
- `SECURITY.md` — security policy and vulnerability reporting.
