# WhisperType — AI Voice to Text for Android

Voice to text for Android that keeps your normal keyboard. Stream your speech to
Google's Gemini Live API and get the transcription inserted at the cursor of
whatever text field is focused — in Messages, Gmail, Notes, WhatsApp, or any
app with a normal text field.

**Private by design:** local-first. Your API key is encrypted on-device with an
Android Keystore AES-GCM key, and transcripts and audio are never stored or
logged by default. There is no WhisperType backend server, no cloud account, and
nothing leaves your device except the audio stream to Google's Gemini Live API.

---

## Highlights

- **Draggable mic bubble** — a freely draggable microphone bubble floats above
  your keyboard. It is the app logo itself (round), and it auto-minimizes to a
  small mini-dot after a few seconds of idle; the dot starts dictation too.
  Bubble size and opacity are configurable.
- **Recording pill** — while dictating, a thin translucent capsule shows
  **[Cancel ✕] [live waveform] [Done ✓]**; Done commits, Cancel discards. The
  pill is anchored so Done sits exactly where you tapped the bubble.
- **Real-time waveform** — a flat line on silence, a dancing multi-peak skyline
  while you speak (sensitive to quiet voices).
- **Never lose a dictation** — a completeness gate verifies the polished
  transcript covers your raw speech, and an audio-recovery failsafe
  re-transcribes the retained recording when the live sources fall short. Long
  dictations are never silently truncated ("Reliability first", 0.4.2).
- **Four output-polish levels** — `NONE` / `LOW` / `MEDIUM` / `HIGH` (default
  `MEDIUM`) passed to the model through the Gemini `systemInstruction`.
- **English + Hinglish** — Hinglish instructs the model to write Hindi words in
  Roman/Latin script.
- **Auto-stop** — stops on silence or at a configurable hard cap
  (15 / 30 / 60 / 120 / 300 s, default 60 s).
- **Custom dictionary** — client-side correction rules with optional
  "always write as" spellings, applied at insertion.
- **Encrypted optional history** — a viewable History page (list, copy, delete,
  delete-all) over an encrypted store; off by default.
- **Dark mode** — an emerald-teal brand theme across every screen, with a light
  and a dark scheme.
- **Bottom navigation** — Home / History / Dictionary / Settings.
- **Android 13+** — `minSdk 33`, works on phones and tablets.

---

## How it works

```
App (Messages, Gmail, Notes…)            WhisperType
+---------------------------------+      +----------------------------+
| [ text field        cursor    ] |      | Mic bubble appears above the|
|                                 |      | keyboard; tap it to speak. |
+---------------------------------+      |                            |
|  QWERTYUIOP  (your keyboard)    |      | Recording pill: ✕ wave ✓  |
+---------------------------------+      |                            |
                                        Audio → Gemini Live API (TLS)
                                        Transcription → inserted at cursor
```

1. Focus any normal text field. The WhisperType bubble appears above the keyboard.
2. Tap the bubble (or the mini-dot) and speak.
3. A recording pill with a live waveform shows while you speak.
4. The validated transcription is inserted at the cursor; your keyboard returns.

**The engine.** Audio streams over TLS to a Gemini Live realtime session. The
model's instructed echo (`outputTranscription`) is the primary dictation source
— the `systemInstruction` tells the model to repeat your speech back with the
selected polish level (Latin script for Hinglish) — with the raw ASR
(`inputTranscription`) as a fast fallback. Because the echo streams as
word-level deltas and the model can condense very long turns, 0.4.2 adds a
**completeness gate** (the echo is accepted only when its content covers the raw
ASR, otherwise the complete raw is salvaged) and an **audio-recovery failsafe**
(the session recording is re-transcribed via a non-live transcription call when
both live sources under-deliver). See `docs/GEMINI_LIVE.md` for the full engine
and wire reference.

---

## Quick start

1. **Install the APK** — copy `app/build/outputs/apk/debug/app-debug.apk` (or a
   signed release APK) to your phone and install it. On Android 13+, allow the
   app used to open the APK to install unknown apps.
2. **Grant permissions** — allow overlay, microphone, and notifications when
   prompted.
3. **Enable the Accessibility Service** — `Settings → Accessibility →
   WhisperType`.
4. **Add your Gemini API key** — Settings tab → Gemini account → Save key. The
   key is encrypted on-device with an Android Keystore AES-GCM key and never
   logged.
5. **Dictate** — focus a text field, tap the bubble, and speak.

See `docs/USER_SETUP.md` for the complete step-by-step walkthrough (permissions,
Accessibility, API key, daily use, and every setting).

---

## Privacy & security

WhisperType has no backend server and no cloud account.

- **API key** — the only secret the app stores. Encrypted with an Android
  Keystore AES-GCM 256-bit key, kept in an app-private no-backup file, never
  placed in DataStore, SharedPreferences, logs, or BuildConfig. Keys are
  rejected unless they start with `AIza`.
- **Transcripts & audio** — never logged; stored only if you enable local
  history (off by default), and then encrypted. The audio-recovery failsafe
  writes a temporary WAV to the app cache during recovery and deletes it in all
  paths.
- **Accessibility** — the service detects editable fields, keyboard bounds, and
  performs final text insertion; it never reads or stores unrelated screen
  content. Secure fields (password, PIN, payment) are excluded by design.
- **Backups** — `allowBackup=false` plus extraction rules exclude every backup
  and device-transfer path.
- **Microphone** — recorded only during an active, user-initiated dictation,
  with a foreground notification; no background recording.

The full policy — key storage, transcripts, history, clipboard, logging
restrictions, and the threat model — is in `docs/SECURITY_AND_PRIVACY.md`.

---

## Documentation map

| Topic | Document |
| --- | --- |
| User setup & daily use | `docs/USER_SETUP.md` |
| Architecture (two processes, modules, session flow) | `docs/ARCHITECTURE.md` |
| Gemini Live voice engine & wire reference | `docs/GEMINI_LIVE.md` |
| Testing, device matrix & on-device protocol | `docs/TESTING.md` |
| Release process, versioning & signing | `docs/RELEASE_PROCESS.md` |
| Security & privacy policy | `docs/SECURITY_AND_PRIVACY.md` |
| Troubleshooting | `docs/TROUBLESHOOTING.md` |
| Contributing (branching, commits, privacy rules) | `docs/CONTRIBUTING.md` |
| Push the build to a phone over wireless ADB | `docs/PUSH_TO_PHONE_VIA_ADB.md` |
| Product requirements (historical reference) | `PRD/WhisperType-Android-PRD.md` |
| Release history | `CHANGELOG.md` |

---

## Build & test

**Prerequisites:** JDK 17, an Android SDK with platform 36 installed (licenses
accepted), and internet access for the Gemini Live API at runtime. The Gradle
wrapper (Gradle 8.14.3, AGP 8.13.2, Kotlin 2.4.10) downloads everything else.

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests (424 tests, device-free)
./gradlew :app:lintDebug            # lint; warnings are treated as errors
./gradlew :app:assembleDebug        # debug APK
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A signed **release** build requires a keystore configured in `signing.properties`
at the repository root (never commit it). See `docs/RELEASE_PROCESS.md` for
signing, versioning, checksums, upgrade tests, and the release checklist.

---

## Supported devices, keyboards & limitations

- **Android 13+** — `minSdk 33`, `targetSdk 36`, `compileSdk 36`.
- **Keyboards** — SwiftKey, Gboard, and Samsung Keyboard in standard docked
  mode (or any IME that can be the device default). Floating and split keyboard
  layouts are not supported and do not show the bubble.
- **Accessibility Service** — required; WhisperType works only while it is
  enabled.
- **Secure fields** — password, PIN, payment, and flag-secure windows never show
  the bubble; they are excluded by design.
- **One session at a time** — each session is a single utterance bounded by the
  auto-stop settings.
- **Languages** — English and Hinglish (Latin script). English mode rejects
  Devanagari; Hinglish is defensive about it.
- **Insertion** — depends on a safe input connection; unusual editors (some
  WebViews, canvas editors) fall back to an explicit Copy action.

---

## Repository layout

```
app/src/main/java/com/whispertype/android/
  platform/     Gemini Live session + wire codec, overlay host, accessibility
                service (own process), runtime coordinator + foreground service
  core/         pure logic: transcript accumulation/selection/completeness,
                dictation state machine, models, dictionary, audio framing,
                privacy redaction, contracts
  data/         settings (DataStore), secrets (Keystore + AES-GCM), encrypted
                history store
  audio/        microphone capture, PCM chunking, pre-ready buffer, session
                recording
  ui/           Compose screens (Home, History, Dictionary, Settings), theme,
                waveform
app/src/test/   JVM unit tests (device-free)
docs/           documentation hub
PRD/            product requirements (historical)
scripts/        build, install, and diagnostics helper scripts
```

---

## Version

Current version **0.4.2** (versionCode 25), developed on the `feature` branch.
See `CHANGELOG.md` for the full release history.
