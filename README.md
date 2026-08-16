# WhisperType — AI Voice to Text for Android

> ## ⚠️ MODEL POLICY — NON-NEGOTIABLE
>
> **This project uses EXACTLY ONE Gemini model: the Gemini Live model**
> (`gemini-3.1-flash-live-preview`, the `BidiGenerateContent` / Live API).
>
> No other Gemini model may be called — **not** `generateContent`, **not** Flash,
> Pro, Flash-Lite, native-audio, TTS, or any other REST/batch variant. The Gemini
> Live model is the only one included at no cost with the project owner's API key.
> **Any other Gemini model would incur charges and is explicitly refused.**
>
> - **Audio** goes ONLY to Gemini Live. Never to any other service.
> - **Text** polishing/romanization runs ONLY on Groq's free tier.
> - If a change requires a different model, **it does not ship.**
>
> This is enforced by `ModelPolicyTest` — the build fails if any other Gemini
> model ID or a `:generateContent` endpoint appears in the source.

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
- **Bluetooth headset mic** — by default dictation records from the phone mic.
  Set `Settings → Recording → Recording source` to **Bluetooth headset** and the
  connected headset's mic is used (falling back to the phone mic when no headset
  is connected).
- **Physical-keyboard hotkey** — a single hardware key (default: the grave/backtick
  key, configurable in `Settings → Recording → Keyboard shortcut`) toggles
  dictation: press once to start, again to complete. Works without the soft
  keyboard being visible; secure fields are still excluded.
- **Real-time waveform** — a flat line on silence, a dancing multi-peak skyline
  while you speak (sensitive to quiet voices).
- **Never lose a dictation** — an echo completeness gate accepts the polished
  transcript only when its content covers your raw speech; otherwise the complete
  raw ASR is salvaged. There is never a retry just because the echo was
  truncated, so long dictations are never silently cut short ("Reliability
  first", 0.4.2).
- **Four output-polish levels** — `NONE` / `LOW` / `MEDIUM` / `HIGH` (default
  `MEDIUM`) passed to the model through the Gemini `systemInstruction`.
- **English + Hinglish** — output is guaranteed Latin script: Hinglish settles
  only from the instructed echo (the raw ASR is never used for Hinglish because
  it comes back in Devanagari), with a live-model transliteration fallback.
- **Auto-stop** — stops on silence or at a configurable hard cap
  (15 / 30 / 60 / 120 / 300 s, default 60 s).
- **Custom dictionary** — client-side correction rules with optional
  "always write as" spellings, applied at insertion.
- **Encrypted history** — a viewable History page (list, copy, delete, delete-all)
  over an encrypted store; on by default (30-day retention), switchable in Settings.
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

**The engine (0.8.0).** Audio streams over TLS to a Gemini Live realtime
session that uses only the **`gemini-3.1-flash-live-preview`** live model, and
that session is **raw-ASR transport only**: WhisperType reads the server's
`inputTranscription` and never enables the model's echo channel or sends a
`systemInstruction`. When you press Done, settlement waits a single **250 ms**
quiet window (with one 2.5 s tail backstop) — the 0.6.2 echo barriers, the
generation gate, the 20 s deadline and the second transliteration session are
all gone, which is where the old 5-10 s wait came from. See
`docs/GEMINI_LIVE.md` for the wire reference.

**The text stage.** What happens to the settled text is decided by
`Settings → Transcription style`:

| Level | Behaviour | Network |
| --- | --- | --- |
| **None** | Inserted exactly as recognized | none |
| **Low** | Removes `um`/`uh`/`ah` and stutters, fixes punctuation — nothing else | 1 Groq call |
| **Medium** | Fixes grammar and word choice **without restructuring** your sentences | 1 Groq call |
| **High** | Rewrites into clean written prose from what you said | 1 Groq call |

Hinglish additionally romanizes Devanagari into colloquial Latin at every level,
in the same single call, keeping English words as English. The stage runs on
Groq's free tier (`llama-3.1-8b-instant`, measured 150-260 ms) and is
**text-only — your audio never goes to Groq**. A reply that edits more than the
level allows is rejected by `PolishGuard` and your unpolished words are inserted
instead, so a slow, failed, rate-limited or over-eager model can never lose or
rewrite your speech. The Groq key is stored in the Android Keystore, and no
transcript text ever appears in logs.

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
- **Transcripts & audio** — never logged; settled dictations are stored encrypted
  in local history (on by default, 30-day retention, switchable in the History
  tab). No recording is retained beyond the live session, so audio is never
  stored.
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

Current version **0.5.6** (versionCode 32), developed on the `feature` branch.
See `CHANGELOG.md` for the full release history.
