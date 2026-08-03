<proposed_plan>
# WhisperType Android — Implementation Plan

## 1. Product goal and locked decisions

Create a completely standalone Android project at:

`C:\Users\Kunal\Desktop\Projects\WhisperType-Android`

The existing desktop WhisperType project must not be modified or used as a build dependency. Only its product behavior and Gemini prompting/validation rules may be referenced conceptually.

The Android app will:

- Keep SwiftKey, Gboard, or Samsung Keyboard selected as the active keyboard.
- Show a small WhisperType microphone control docked to the upper boundary of the active keyboard.
- Never use a permanently floating bubble over the application content.
- Expand the docked control into a full-width voice panel that visually covers the keyboard while the user dictates.
- Keep the underlying keyboard technically active so the text field, caret, and input connection remain available.
- Restore the keyboard immediately after insertion, cancellation, or failure.
- Use tap-to-start and tap-to-stop dictation.
- Show a waveform and recording state, but no live transcript text inside the voice panel.
- Use Gemini Live directly from the Android device.
- Require the user’s Gemini API key in Settings.
- Store the key only in Android Keystore-backed encrypted storage.
- Support English and Hinglish using Latin script only.
- Validate cleaned and raw transcript candidates using the same safety principles as desktop WhisperType.
- Keep transcription history disabled by default.
- Provide an explicit Copy fallback when direct insertion is unavailable.
- Target Android 14 and newer.
- Target Pixel and Samsung devices first.
- Support standard docked Gboard, SwiftKey, and Samsung Keyboard layouts.
- Be distributed initially as privately signed APKs through sideloading.
- Use local Git only; no remote repository is required for v1.

Application identity:

- Product name: `WhisperType`
- Android application ID: `com.whispertype.android`
- Initial version: `0.1.0`
- Technology: Native Kotlin, Jetpack Compose, Android SDK
- Minimum SDK: API 34
- Compile/target SDK: latest stable SDK available when the project is created, pinned in the project’s version catalog

## 2. Important platform truth

Android does not provide a public API that allows WhisperType to add a real button inside SwiftKey, Gboard, or Samsung Keyboard.

Therefore, the implementation will create a perceptually integrated companion control:

- The Accessibility Service detects the active keyboard window.
- It reads the keyboard’s screen bounds.
- It positions a `TYPE_ACCESSIBILITY_OVERLAY` control immediately above or partly over that keyboard.
- The overlay is constrained to the keyboard area and never becomes a free-floating app bubble.
- The keyboard remains the selected IME underneath the overlay.
- The overlay must not take focus away from the text field.
- The voice panel visually covers the keyboard during dictation.
- The original keyboard remains initialized and returns without keyboard switching.

This is a visual integration with the keyboard, not a modification of SwiftKey or Gboard internals.

The following two assumptions are mandatory technical-spike gates:

1. The accessibility overlay must render above the supported keyboard windows and receive touches on both Pixel and Samsung devices.
2. A microphone foreground service must be startable from the explicit dock interaction while the app’s main Activity is not visible.

If either gate fails consistently, stop implementation and reassess the interaction architecture before building the rest of the product.

## 3. Repository and project setup

### 3.1 Create the repository

Create the directory:

`C:\Users\Kunal\Desktop\Projects\WhisperType-Android`

Initialize a local Git repository:

```powershell
cd C:\Users\Kunal\Desktop\Projects\WhisperType-Android
git init -b main
```

Create an integration branch before implementation:

```powershell
git checkout -b feature/android-foundation
```

Use `main` only for verified milestones. All implementation work must happen on feature branches.

Recommended branches:

- `feature/android-foundation`
- `feature/feasibility-spike`
- `feature/accessibility-service`
- `feature/docked-overlay`
- `feature/gemini-live`
- `feature/audio-pipeline`
- `feature/insertion-fallback`
- `feature/settings-onboarding`
- `feature/history`
- `feature/qa-release`

Do not commit API keys, keystores, APK signing passwords, or local device data.

### 3.2 Initial project files

Create:

```text
WhisperType-Android/
├── app/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
├── docs/
│   ├── IMPLEMENTATION_PLAN.md
│   ├── USER_SETUP.md
│   ├── ARCHITECTURE.md
│   ├── ACCESSIBILITY_DESIGN.md
│   ├── GEMINI_LIVE_PROTOCOL.md
│   ├── SECURITY_AND_PRIVACY.md
│   ├── TESTING.md
│   ├── DEVICE_COMPATIBILITY.md
│   ├── RELEASE_PROCESS.md
│   └── TROUBLESHOOTING.md
├── scripts/
│   ├── verify-secrets.ps1
│   ├── build-release.ps1
│   ├── install-release.ps1
│   └── collect-diagnostics.ps1
├── README.md
├── CHANGELOG.md
├── SECURITY.md
├── CONTRIBUTING.md
├── .gitignore
└── LICENSE
```

Use one Android `app` module initially. Keep code organized by feature rather than prematurely creating multiple Gradle modules.

### 3.3 Recommended package structure

```text
app/src/main/java/com/whispertype/android/
├── MainActivity.kt
├── WhisperTypeApplication.kt
├── accessibility/
│   ├── WhisperTypeAccessibilityService.kt
│   ├── AccessibilityEventRouter.kt
│   ├── InputTargetTracker.kt
│   ├── InputMethodWindowTracker.kt
│   ├── TargetToken.kt
│   └── AccessibilityInsertionController.kt
├── overlay/
│   ├── OverlayWindowController.kt
│   ├── OverlayGeometryCalculator.kt
│   ├── DockedMicOverlay.kt
│   ├── VoicePanelOverlay.kt
│   ├── OverlayTouchPolicy.kt
│   └── OverlayStateRenderer.kt
├── dictation/
│   ├── DictationForegroundService.kt
│   ├── DictationCoordinator.kt
│   ├── DictationState.kt
│   ├── DictationCommand.kt
│   ├── DictationSession.kt
│   ├── DictationReducer.kt
│   └── DictationFailure.kt
├── audio/
│   ├── AudioCapture.kt
│   ├── AudioChunk.kt
│   ├── AudioQueue.kt
│   ├── WaveformMeter.kt
│   └── AudioDiagnostics.kt
├── gemini/
│   ├── GeminiLiveClient.kt
│   ├── GeminiLiveWebSocket.kt
│   ├── GeminiLiveProtocol.kt
│   ├── GeminiSessionConfig.kt
│   ├── GeminiTranscriptAssembler.kt
│   └── GeminiErrorMapper.kt
├── validation/
│   ├── TranscriptValidator.kt
│   ├── CleanedTranscriptValidator.kt
│   ├── HinglishValidator.kt
│   ├── CandidateSelector.kt
│   └── CorruptionRules.kt
├── security/
│   ├── SecretStore.kt
│   ├── KeystoreManager.kt
│   ├── SensitiveClipboard.kt
│   └── SecretRedactor.kt
├── settings/
│   ├── SettingsRepository.kt
│   ├── SettingsViewModel.kt
│   ├── SettingsScreen.kt
│   ├── DockSettings.kt
│   ├── LanguageSettings.kt
│   └── PrivacySettings.kt
├── onboarding/
│   ├── OnboardingViewModel.kt
│   ├── OnboardingScreen.kt
│   ├── PermissionLauncher.kt
│   └── CompatibilityTestScreen.kt
├── history/
│   ├── HistoryRepository.kt
│   ├── HistoryDatabase.kt
│   ├── EncryptedHistoryStore.kt
│   └── HistorySettings.kt
└── diagnostics/
    ├── TimingMetrics.kt
    ├── DiagnosticEvent.kt
    └── DiagnosticsExporter.kt
```

## 4. Subagent development model

The lead agent owns integration, shared contracts, the Gradle project, manifest, versioning, and final verification.

Subagents must not edit overlapping files.

### Workstream A — Android feasibility spike

Owns:

- `app/src/main/java/.../accessibility/`
- `app/src/main/java/.../overlay/`
- `docs/ACCESSIBILITY_DESIGN.md`

Responsibilities:

- Detect the active IME window.
- Read IME bounds.
- Draw a touchable accessibility overlay above Gboard and SwiftKey.
- Verify the overlay does not steal editor focus.
- Verify the underlying input connection remains usable.
- Verify the foreground microphone service can start from the dock tap.
- Test Pixel and Samsung in portrait and landscape.

Deliverable:

- A working spike screen and a written go/no-go report.

### Workstream B — Gemini Live and transcript behavior

Owns:

- `gemini/`
- `validation/`
- `docs/GEMINI_LIVE_PROTOCOL.md`

Responsibilities:

- Implement direct Gemini Live WebSocket communication.
- Port English/Hinglish behavior.
- Implement raw/cleaned candidate selection.
- Add a local fake WebSocket server for tests.
- Ensure no key or authenticated URL logging.

### Workstream C — Audio pipeline

Owns:

- `audio/`
- audio sections of `dictation/`

Responsibilities:

- Implement `AudioRecord`.
- Capture mono PCM16 in 20 ms chunks.
- Maintain a bounded queue capped at two seconds.
- Expose throttled waveform amplitude.
- Drain the queue before sending Gemini activity end.
- Detect queue overflow and chunk-loss conditions.

### Workstream D — Settings, onboarding, security, and history

Owns:

- `settings/`
- `onboarding/`
- `security/`
- `history/`
- `docs/USER_SETUP.md`
- `docs/SECURITY_AND_PRIVACY.md`

Responsibilities:

- Build the Compose settings screens.
- Store Gemini key with Keystore-backed encryption.
- Implement Accessibility and microphone permission onboarding.
- Keep history off by default.
- Implement optional encrypted local history.
- Provide clear permission recovery paths.

### Workstream E — QA and device compatibility

Owns:

- `app/src/androidTest/`
- `app/src/test/`
- `docs/TESTING.md`
- `docs/DEVICE_COMPATIBILITY.md`

Responsibilities:

- Build the fake editor/test host app.
- Test focus changes, keyboard changes, rotation, secure fields, and stale sessions.
- Maintain Pixel/Samsung test matrix.
- Perform privacy and log audits.
- Report failures by device, Android version, keyboard, and reproduction steps.

### Required subagent report format

Every subagent must report:

```text
Changed files:
Behavioral decisions:
Tests run:
Failures:
Known limitations:
Integration risks:
Required follow-up:
```

## 5. Phase 0 — Technical feasibility spike

Do not implement the entire product before completing this phase.

### 5.1 Build the smallest Android app

Create:

- `MainActivity`
- Accessibility Service declaration
- Microphone permission
- Minimal foreground microphone service
- One simple Compose overlay
- One test text field inside a local demo Activity

### 5.2 Verify Accessibility Service configuration

The service must declare:

- `canRetrieveWindowContent=true`
- `canRequestFilterKeyEvents=false`
- `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`
- `FLAG_INPUT_METHOD_EDITOR`

Do not request broad screen-reading behavior beyond what is needed for:

- editable-field detection,
- IME-window bounds,
- input connection access,
- insertion.

Do not store or log AccessibilityNode trees.

### 5.3 Detect the keyboard

On accessibility window and focus events:

1. Call `getWindows()`.
2. Locate `AccessibilityWindowInfo.TYPE_INPUT_METHOD`.
3. Read `getBoundsInScreen()`.
4. Confirm that the bounds are:
   - on the active display,
   - attached to the bottom of the display,
   - wider than a minimum usable width,
   - tall enough to be a normal keyboard,
   - not a floating or split keyboard.
5. Debounce geometry updates until stable.
6. Hide the dock if geometry is invalid or ambiguous.

Acceptance:

- 50 consecutive keyboard open/close cycles.
- Bounds stable within approximately 8 dp/pixels of the visible keyboard edge.
- Pixel with Gboard and SwiftKey.
- Samsung with Gboard, SwiftKey, and Samsung Keyboard.

### 5.4 Verify overlay layering

Use:

```text
TYPE_ACCESSIBILITY_OVERLAY
FLAG_NOT_FOCUSABLE
FLAG_NOT_TOUCH_MODAL
FLAG_LAYOUT_IN_SCREEN
```

The overlay must:

- appear above the IME,
- receive touches inside its own bounds,
- allow touches outside its bounds to reach the app,
- never invoke keyboard switching,
- never move the editor cursor,
- never dismiss the keyboard,
- never take input focus.

Test:

- Docked control.
- Expanded full-keyboard panel.
- Rotation.
- Keyboard resizing.
- One-handed mode.
- System gesture navigation.
- Three-button navigation.
- Screen lock and unlock.
- App switching.

### 5.5 Verify microphone foreground-service start

From the dock tap while the main Activity is backgrounded:

1. Start the foreground microphone service.
2. Show the required recording notification.
3. Start `AudioRecord`.
4. Confirm Android’s microphone privacy indicator appears.
5. Stop cleanly from the overlay.
6. Confirm the service terminates.

This must work on both Pixel and Samsung target devices.

If it fails, do not use hidden background workarounds. Reassess the trigger as either:

- a visible notification action,
- a foreground Activity transition,
- or a momentary IME fallback.

## 6. Phase 1 — Project foundation

Implement:

- Gradle Kotlin DSL.
- Version catalog.
- Kotlin and Compose configuration.
- Static analysis.
- Unit-test configuration.
- Instrumented-test configuration.
- Debug and release build types.
- Application ID `com.whispertype.android`.
- Android 14 minimum SDK.
- Latest stable compile and target SDK.
- Material 3 Compose theme.
- Light/dark theme support.
- System font scaling support.
- Strict lint and compiler warnings.

Add `.gitignore` rules for:

- `.idea/`
- `.gradle/`
- `local.properties`
- build outputs
- APKs
- keystores
- signing properties
- Gemini keys
- generated diagnostic files
- emulator snapshots
- transcript fixtures containing real user data

Create the first commit only after the project builds and the empty test suite passes.

## 7. Phase 2 — Settings and onboarding

### 7.1 Main app purpose

The main Activity is for:

- onboarding,
- settings,
- permission recovery,
- dock customization,
- language selection,
- optional history settings,
- compatibility diagnostics,
- test dictation.

It is not used as the normal dictation surface.

### 7.2 Onboarding flow

1. Explain that WhisperType keeps the existing keyboard active.
2. Explain that Accessibility access is needed for:
   - detecting the editable field,
   - detecting keyboard bounds,
   - inserting the final text.
3. Explain that WhisperType does not record continuously.
4. Request microphone permission.
5. Request notification permission.
6. Open Android Accessibility settings.
7. Verify service activation after returning.
8. Ask for the Gemini API key.
9. Store it immediately using `SecretStore`.
10. Let the user choose English or Hinglish.
11. Show a live dock placement preview.
12. Run a demo dictation in a local test field.
13. Verify successful insertion.
14. Confirm the user’s existing keyboard remains selected.

If permission is declined:

- Do not repeatedly nag.
- Show the missing permission and an action to reopen Settings.
- Keep the app usable for configuration and troubleshooting.

### 7.3 Settings

Required settings:

- Gemini API key:
  - masked input,
  - show/hide toggle,
  - replace key,
  - delete key,
  - test connection.
- Speech mode:
  - English,
  - Hinglish.
- Dock side:
  - Left,
  - Center,
  - Right.
- Dock size:
  - Compact,
  - Standard,
  - Large.
- Dock vertical overlap:
  - Mostly over keyboard,
  - Mostly above keyboard.
- Dock opacity.
- Theme:
  - System,
  - Light,
  - Dark.
- Accent color from a small curated palette.
- Haptic feedback.
- Optional start/stop sounds, off by default.
- Show elapsed time in voice panel.
- Per-app disable list.
- Optional local history:
  - off by default,
  - retention period,
  - clear all history.
- Open Accessibility settings.
- Open microphone settings.
- Run compatibility test.
- Export diagnostics.

## 8. Phase 3 — Secure credential storage

Implement `SecretStore` using:

1. An Android Keystore AES-GCM encryption key.
2. Random IV per encryption.
3. Ciphertext and IV stored in app-private no-backup storage.
4. No plaintext Gemini key in DataStore, SharedPreferences, Room, logs, resources, BuildConfig, or environment variables.
5. Disable Android backup/device transfer for secret storage.
6. Clear all plaintext references after use where practical.
7. Redact authorization headers and WebSocket URLs from logs and exceptions.

Tests:

- Store and retrieve key.
- Replace key.
- Delete key.
- Corrupted ciphertext.
- Keystore unavailable.
- Device lock state.
- Backup exclusion.
- Log scanning for key patterns.

## 9. Phase 4 — Accessibility service and input tracking

### 9.1 Service responsibilities

`WhisperTypeAccessibilityService` owns:

- service lifecycle,
- active editor tracking,
- IME window tracking,
- target validation,
- overlay WindowManager,
- insertion through Accessibility InputConnection,
- cancellation when focus becomes invalid.

It must not own:

- microphone capture,
- Gemini WebSocket code,
- transcript history,
- API key storage.

### 9.2 Target token

At dictation start, capture:

```kotlin
data class TargetToken(
    val sessionId: UUID,
    val inputGeneration: Long,
    val packageName: String,
    val displayId: Int,
    val windowId: Int?,
    val fieldId: Int?,
    val inputType: Int,
    val initialSelectionStart: Int,
    val initialSelectionEnd: Int,
    val imeBounds: Rect
)
```

Before insertion:

- reacquire the current input connection,
- compare package,
- compare display,
- compare input generation,
- compare active editor,
- verify screen is unlocked,
- verify field is not secure,
- verify session has not been cancelled,
- verify result has not already been consumed.

If any check fails, do not insert.

### 9.3 Secure-field rules

Never show the dock or start recording in:

- password fields,
- PIN fields,
- banking secure fields,
- payment card fields,
- authentication fields,
- private browsing secure inputs,
- fields marked sensitive by Android,
- fields where Accessibility cannot reliably determine editability.

## 10. Phase 5 — Docked control and voice panel

### 10.1 Docked state

Default behavior:

- 48 dp minimum touch target.
- Centered at the top edge of the keyboard.
- 12 dp from the safe display edge when left/right aligned.
- Static microphone icon.
- No permanent label.
- No timer.
- No waveform.
- No pulsing animation.
- Appears only when:
  - an eligible text field is focused,
  - a supported docked keyboard is visible,
  - Accessibility service is active,
  - microphone permission is available,
  - Gemini key is configured.

### 10.2 Customization

Users may configure:

- left/center/right placement,
- compact/standard/large size,
- keyboard overlap amount,
- opacity,
- light/dark/system theme,
- accent color,
- haptic feedback,
- optional sounds,
- elapsed-time visibility,
- cancel-control side,
- per-app disable list.

The dock may be repositioned only horizontally among safe snapped positions. It must never become freely draggable across arbitrary application content.

### 10.3 Voice panel

On tap:

1. Capture the target token.
2. Transition to `Starting`.
3. Start the foreground microphone service.
4. Keep the existing IME technically active.
5. Replace the visible keyboard area with an opaque WhisperType panel.
6. Show:
   - waveform,
   - listening state,
   - stop button,
   - cancel,
   - optional elapsed time.
7. Do not show transcript text.
8. Do not resize the application content.
9. Do not move or dismiss the editor.
10. Do not allow accidental taps to reach the covered keyboard.

The panel should animate in approximately 180–240 ms and respect reduced-motion settings.

### 10.4 State machine

```text
Unavailable
    ↓
NoEditableFocus
    ↓
DockedReady
    ↓ tap
Starting
    ↓
Listening
    ↓ stop
Finalizing
    ↓
Inserting
    ↓
Success
```

Terminal alternatives:

```text
Cancelled
Error
CopyAvailable
```

Only one dictation session may exist globally.

Every command and result must contain a `sessionId`.

Stale session events must be ignored.

## 11. Phase 6 — Foreground dictation service

`DictationForegroundService` owns:

- one active dictation session,
- microphone capture,
- bounded audio queue,
- Gemini Live WebSocket,
- waveform amplitude,
- final transcript candidate,
- cancellation,
- timing metrics,
- recording notification.

Manifest requirements:

```text
RECORD_AUDIO
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MICROPHONE
POST_NOTIFICATIONS
INTERNET
```

The service must show a visible microphone foreground notification while recording.

Notification actions:

- Stop.
- Cancel.
- Open WhisperType.

The notification must not contain transcript content or API keys.

Recording rules:

- Start only after explicit user interaction.
- One session at a time.
- Mono PCM16.
- 20 ms audio chunks.
- Bounded queue with two-second capacity.
- No silent chunk dropping.
- Stop capture before sending the Gemini activity-end boundary.
- Drain all queued chunks first.
- Send exactly one activity-end boundary.
- Apply a hard maximum recording duration.
- Normal completion happens only through Stop.

If the microphone becomes unavailable:

- stop capture,
- cancel Gemini,
- collapse panel,
- show a concise actionable error,
- never insert partial output.

## 12. Phase 7 — Gemini Live integration

Implement a direct device-to-Gemini Live client.

The key is loaded from `SecretStore` only when a session starts.

One WebSocket is created per utterance.

Protocol flow:

1. Connect.
2. Send setup message.
3. Configure audio input format.
4. Configure English or Hinglish behavior.
5. Enable raw input transcription.
6. Enable cleaned output transcription in the same session.
7. Stream 20 ms audio chunks.
8. Receive raw and cleaned transcript events.
9. Receive turn completion.
10. Stop sending audio.
11. Send the explicit activity-end boundary exactly once.
12. Await final result with a fixed timeout.
13. Select the valid cleaned candidate, otherwise valid raw fallback.
14. Pass the result to insertion.

Do not:

- create a second cleanup request,
- resend an utterance after Gemini may have received it,
- retry after audio transmission,
- log authenticated WebSocket URLs,
- log complete user transcripts,
- store raw audio.

The model identifier must be centralized in one configuration object and documented with the official Gemini Live model chosen during implementation. Updating the model must require changing one documented constant and associated contract tests.

## 13. Phase 8 — Audio and waveform

Use `AudioRecord`.

Requirements:

- mono input,
- PCM16,
- Gemini-supported sample rate,
- 20 ms chunks,
- bounded channel,
- backpressure visibility,
- queue high-water metrics,
- chunk count,
- capture duration,
- drain duration,
- end-boundary timestamp.

The UI receives only throttled waveform samples, approximately 20 updates per second.

It must not receive:

- raw audio,
- complete transcript,
- editor text,
- API key,
- authentication data.

Waveform behavior:

- idle when starting,
- responsive amplitude during recording,
- settling animation during finalization,
- static fallback under reduced-motion settings.

## 14. Phase 9 — Transcript validation and selection

Port the desktop quality rules conceptually into Android-specific Kotlin tests.

Reject:

- empty output,
- whitespace-only output,
- punctuation-only output,
- symbol-only output,
- repeated dots or pipes,
- conversational model preambles,
- suspicious expansions,
- malformed corrupted output,
- Devanagari in Hinglish mode,
- implausible length expansion,
- duplicated candidate content.

Preserve:

- legitimate punctuation,
- URLs,
- emails,
- code identifiers,
- numbers,
- short utterances,
- natural Hinglish code-switching in Latin script.

Selection order:

1. Valid cleaned transcript.
2. Valid raw transcript.
3. No insertion and a clear error.

Never insert both.

Clear candidate memory after:

- successful insertion,
- copy fallback,
- cancellation,
- fatal failure.

## 15. Phase 10 — Insertion and fallback

Preferred path:

1. Reacquire current `AccessibilityInputConnection`.
2. Validate the target token.
3. Insert exactly once using `commitText`.
4. Let Android replace the active selection naturally.
5. Verify cursor remains after inserted text where observable.
6. Collapse the voice panel.
7. Restore normal keyboard visibility.

Never:

- inject synthetic uncontrolled keystrokes,
- silently paste,
- insert into a newly focused field,
- rewrite surrounding text unnecessarily,
- insert cleaned and raw results together.

Fallback path:

1. Preserve the final valid result in memory.
2. Transition to `CopyAvailable`.
3. Collapse the voice panel.
4. Copy only after the user chooses Copy.
5. Mark the clipboard content sensitive where supported.
6. Show: `Copied — paste with SwiftKey/Gboard`.
7. Provide Dismiss.
8. Clear the in-memory result after copy/dismiss.

If insertion fails, do not automatically switch keyboards in v1.

## 16. Phase 11 — Optional local history

History is off by default.

If enabled:

- Store only completed valid dictations.
- Store encrypted transcript content.
- Store timestamp, language mode, insertion status, and app package only if the user explicitly enables diagnostics/history metadata.
- Use an Android Keystore-derived encryption key.
- Exclude the database from Android backup.
- Provide retention choices:
  - 1 day,
  - 7 days,
  - 30 days,
  - custom clear.
- Provide `Clear all history`.
- Never include history in logs or diagnostic exports.
- Never store audio.
- Never store API keys.
- Never store full editor context.

If history is disabled, no transcript database or transcript file should be created.

## 17. Phase 12 — UX states and interruption handling

Required states:

- Service unavailable.
- No editable field.
- Keyboard appearing.
- Docked ready.
- Starting.
- Listening.
- Finalizing.
- Inserting.
- Success.
- Cancelled.
- Copy available.
- Recoverable error.
- Fatal setup error.

Cancel recording when:

- screen locks,
- user changes app,
- editor focus changes,
- keyboard disappears for more than 500 ms,
- another app claims the microphone,
- accessibility service disconnects,
- permission is revoked,
- user presses Back,
- Gemini session becomes unrecoverable.

Focus changes during recording must never result in insertion into the wrong field.

A result from an old session must never be inserted after a new session begins.

## 18. Phase 13 — Testing strategy

### 18.1 Unit tests

Test:

- dictation reducer transitions,
- stale session rejection,
- exactly-once result consumption,
- target-token comparison,
- secure-field classification,
- dock placement calculation,
- keyboard geometry validation,
- overlay safe-inset calculation,
- transcript corruption rules,
- Hinglish Latin-only validation,
- cleaned/raw fallback,
- audio queue overflow,
- queue drain before activity end,
- timeout and cancellation,
- encrypted secret storage,
- sensitive clipboard behavior.

### 18.2 Gemini contract tests

Use a local fake WebSocket server.

Test:

- setup ordering,
- audio message format,
- single activity-end boundary,
- raw and cleaned transcript events,
- turn completion,
- cleaned candidate fallback,
- invalid candidate rejection,
- protocol error,
- timeout,
- cancellation,
- network loss,
- no retry after audio has been sent,
- no API key or authenticated URL in logs.

No CI test may require a live Gemini API key.

### 18.3 Instrumented test application

Create a test host Activity containing:

- single-line EditText,
- multiline EditText,
- selected-text EditText,
- password field,
- numeric/PIN field,
- WebView contenteditable field,
- focus-switch controls,
- app-switch simulation,
- rotation test controls,
- keyboard show/hide controls.

Assert:

- dock appears only for eligible fields,
- dock disappears on focus loss,
- overlay does not steal focus,
- outside touches pass through,
- voice panel covers keyboard,
- target field remains active,
- insertion replaces selection correctly,
- stale sessions never insert,
- password fields never show the dock,
- copy fallback is explicit,
- cancellation clears the session.

### 18.4 Manual device matrix

Pixel:

- Android 14 or newer,
- Gboard,
- SwiftKey,
- portrait,
- landscape,
- gesture navigation,
- three-button navigation,
- dark/light mode,
- large font,
- TalkBack,
- reduced motion,
- keyboard resize behavior.

Samsung:

- One UI Android 14 or newer,
- Samsung Keyboard,
- Gboard,
- SwiftKey,
- same display/accessibility scenarios.

Also test:

- notification permission denied,
- microphone permission denied,
- Accessibility disabled after setup,
- invalid Gemini key,
- network loss,
- microphone in use by another app,
- incoming phone call,
- screen lock,
- app switch,
- keyboard switch,
- rapid repeated taps,
- process death,
- rotation during recording,
- one-handed keyboard mode,
- floating/split keyboard behavior.

V1 should hide the dock for unsupported floating, split, handwriting, DeX, hardware-keyboard, and ambiguous layouts instead of guessing.

## 19. Phase 14 — Security and privacy audit

Verify:

- API keys do not appear in source, resources, BuildConfig, logs, snapshots, or APK strings.
- No `.env` or environment fallback exists.
- No authenticated Gemini URL is logged.
- No raw audio is logged or persisted.
- No complete transcript is logged.
- No AccessibilityNode tree is logged.
- No clipboard reads occur.
- Clipboard writes are explicit and marked sensitive.
- History is absent when disabled.
- History is encrypted when enabled.
- Secrets and history are excluded from Android backup.
- Crash reports contain only typed error codes and aggregate timing metadata.
- The microphone foreground notification appears during recording.
- The Android privacy indicator appears during recording.
- Accessibility disclosure accurately describes the service’s purpose.
- The service does not inspect unrelated screen content.

## 20. Phase 15 — Documentation

Create and maintain:

### `README.md`

Include:

- Product summary.
- Screenshot or interaction diagram.
- Supported Android versions.
- Supported keyboards.
- Known limitations.
- Build prerequisites.
- Debug build instructions.
- Release build instructions.
- Install instructions.
- Uninstall instructions.
- Link to `docs/USER_SETUP.md`.

### `docs/ARCHITECTURE.md`

Document:

- Component ownership.
- Service boundaries.
- Overlay lifecycle.
- Input-target tracking.
- State machine.
- Binder/StateFlow communication.
- Gemini flow.
- Insertion safety.
- Cancellation/session isolation.

### `docs/ACCESSIBILITY_DESIGN.md`

Document:

- Why Accessibility access is required.
- Exact service capabilities requested.
- Window/IME detection.
- Overlay window type and flags.
- Secure-field exclusion.
- InputConnection restrictions.
- OEM differences.
- Technical spike results.

### `docs/GEMINI_LIVE_PROTOCOL.md`

Document:

- setup sequence,
- audio format,
- message types,
- transcript events,
- activity-end behavior,
- completion behavior,
- timeouts,
- retry policy,
- validation and fallback.

### `docs/SECURITY_AND_PRIVACY.md`

Document:

- API key storage,
- backup exclusion,
- transcript handling,
- history settings,
- clipboard behavior,
- logging restrictions,
- accessibility disclosure,
- microphone notification behavior.

### `docs/TESTING.md`

Document:

- unit tests,
- fake WebSocket tests,
- instrumented host app,
- device matrix,
- manual test scripts,
- release checklist.

### `docs/DEVICE_COMPATIBILITY.md`

Maintain a table containing:

- phone model,
- Android version,
- keyboard,
- navigation mode,
- overlay result,
- insertion result,
- known issues,
- last tested date.

### `docs/RELEASE_PROCESS.md`

Document:

- versioning,
- signing,
- APK generation,
- checksum generation,
- installation,
- upgrade testing,
- rollback/uninstall,
- release notes.

### `docs/USER_SETUP.md`

Use the user guide supplied below.

## 21. Git workflow and maintenance

Use local Git with stable `main` and feature branches.

Each feature branch must:

1. Start from the latest verified integration commit.
2. Have one clear scope.
3. Include tests and documentation for its behavior.
4. Avoid unrelated formatting changes.
5. Be reviewed by the lead agent before integration.
6. Be merged only after focused tests pass.

Recommended commit style:

```text
feat(accessibility): detect active IME bounds
feat(overlay): add docked microphone control
feat(dictation): add Gemini Live foreground session
fix(insertion): reject stale input targets
test(gemini): cover activity-end ordering
docs(setup): document accessibility onboarding
```

Versioning:

- `0.1.0`: first private prototype.
- `0.2.0`: verified docked overlay and Gemini dictation.
- `0.3.0`: compatibility and fallback hardening.
- `1.0.0`: stable private release after Pixel/Samsung acceptance.

Maintain `CHANGELOG.md`.

Do not commit:

- release keystore,
- signing passwords,
- Gemini API keys,
- private transcripts,
- real audio,
- device logs containing user data,
- generated APKs unless explicitly desired.

## 22. Release and deployment

### 22.1 Release keystore

Create a dedicated release keystore outside the repository.

Store:

- keystore path,
- alias,
- passwords,
- backup instructions,

in a secure password manager, not Git.

Configure release signing through a local ignored properties file.

Never delete the original release keystore. It is required for app upgrades.

### 22.2 Build

Run:

```powershell
./gradlew clean test lint
./gradlew connectedCheck
./gradlew assembleRelease
```

Generate a SHA-256 checksum:

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Rename the final artifact:

```text
WhisperType-Android-0.1.0-release.apk
```

### 22.3 Install

Enable USB debugging on the test phone.

Install:

```powershell
adb install -r .\WhisperType-Android-0.1.0-release.apk
```

For a clean reinstall:

```powershell
adb uninstall com.whispertype.android
adb install .\WhisperType-Android-0.1.0-release.apk
```

Do not uninstall during upgrade tests. Verify the same signed package upgrades without losing settings unexpectedly.

### 22.4 Release checklist

Before each private release:

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

### 22.5 Deployment execution protocol and user checkpoints

Development should proceed without requiring a connected phone for project setup, local unit tests, lint, static analysis, and emulator-compatible UI work. A real phone becomes mandatory when validating Accessibility Service behavior, third-party keyboard geometry, overlay layering, microphone foreground-service startup, text insertion, OEM behavior, and release installation.

The implementation agent must use the following protocol:

1. Complete all safe host-side work that does not require a phone.
2. Before the first physical-device task, stop and tell the user exactly what is needed:
   - plug the Android phone into the computer with a data-capable USB cable,
   - unlock the phone,
   - enable Developer options and USB debugging,
   - select File transfer/Android Auto USB mode if the device is charging only,
   - keep the screen unlocked for the first authorization,
   - accept the `Allow USB debugging?` prompt and optionally select `Always allow from this computer`.
3. Wait for the user to confirm that the phone is connected and ready. Do not repeatedly poll indefinitely while no device is expected.
4. Run the ADB preflight below and report any actionable device-side prompt.
5. Perform the requested install, launch, test, or diagnostic task.
6. Tell the user when hands-on interaction is required, including enabling Accessibility, granting microphone/notification permissions, choosing the normal keyboard, focusing a text field, speaking a test phrase, rotating the phone, or switching apps.
7. Resume automated ADB checks after the user completes the physical interaction.
8. Never uninstall the app, clear app data, revoke permissions, or overwrite a release artifact unless that destructive step is part of the current test and has been stated explicitly.

### 22.6 Host and ADB preflight

Before connecting a phone, verify:

```powershell
java -version
./gradlew --version
adb version
adb start-server
```

Prefer the Android SDK Platform Tools version resolved from the configured Android SDK. If `adb` is not on `PATH`, locate it through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or the SDK path in `local.properties`, and use the absolute executable path. Do not download a second copy when a working SDK copy already exists.

After the user connects and unlocks the phone, run:

```powershell
adb devices -l
```

Handle each state explicitly:

- No device: ask the user to confirm the cable supports data, unlock the phone, change USB mode, and reconnect.
- `unauthorized`: ask the user to accept the USB debugging prompt on the phone. If no prompt appears, reconnect the cable or revoke USB debugging authorizations in Developer options and try again.
- `offline`: restart the ADB server once, reconnect the phone, and recheck.
- More than one device/emulator: select the intended serial and pass `-s <serial>` to every command. Never install to all devices accidentally.
- `device`: record the serial and continue.

Capture the device baseline:

```powershell
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell settings get secure default_input_method
```

Add the result to `docs/DEVICE_COMPATIBILITY.md`, excluding the device serial and other unique identifiers from committed documentation.

### 22.7 Debug deployment loop

Use debug builds during implementation:

```powershell
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb -s <serial> install -r -t .\app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p com.whispertype.android -c android.intent.category.LAUNCHER 1
```

If a launchable activity is known, prefer an explicit `am start` command over `monkey`.

Use `install -r` to preserve app data. Use `install -r -d` only for an intentional version-code downgrade. A signature mismatch requires uninstalling the existing package; because uninstalling removes app data and permissions, tell the user and obtain confirmation before doing it.

For each meaningful device build:

1. Build and install the debug APK.
2. Confirm the package is installed:

   ```powershell
   adb -s <serial> shell pm path com.whispertype.android
   adb -s <serial> shell dumpsys package com.whispertype.android
   ```

3. Launch the app and complete onboarding.
4. Ask the user to enable the WhisperType Accessibility Service when Android opens the settings screen; Accessibility permission must not be granted through hidden or unsupported ADB workarounds.
5. Ask the user to grant microphone and notification permissions through the normal UI unless a specific automated test is deliberately testing permission state.
6. Confirm the user's existing SwiftKey, Gboard, or Samsung Keyboard remains the default IME.
7. Run the compatibility test and at least one real third-party app test.
8. Record results in the device matrix.

### 22.8 ADB-assisted physical-device test sessions

Before collecting logs, clear only the log buffer, not application data:

```powershell
adb -s <serial> logcat -c
```

Collect a scoped log while reproducing a problem:

```powershell
$whisperTypePid = (adb -s <serial> shell pidof -s com.whispertype.android).Trim()
adb -s <serial> logcat --pid=$whisperTypePid
```

If the process is not running, launch the app before resolving the PID. Resolve the PID again after a process restart. Fall back to tag/package filtering when a stable PID is unavailable.

The app must use stable, non-sensitive diagnostic tags so logs can be filtered without emitting transcripts, editor text, audio, API keys, authenticated URLs, AccessibilityNode content, or clipboard content.

Use ADB to support, not replace, user-observed UX checks. The user must confirm visible behavior that ADB cannot reliably judge:

- dock placement relative to the real keyboard,
- whether the overlay visually covers the keyboard,
- touch pass-through outside the overlay,
- whether editor focus and caret remain stable,
- whether the keyboard returns without flicker,
- waveform responsiveness,
- microphone privacy indicator and foreground notification,
- behavior during rotation, app switching, lock/unlock, and navigation-mode changes.

For repeatable evidence, collect only privacy-safe artifacts:

```powershell
adb -s <serial> shell dumpsys window
adb -s <serial> shell dumpsys accessibility
adb -s <serial> shell dumpsys activity services com.whispertype.android
adb -s <serial> shell dumpsys package com.whispertype.android
adb -s <serial> bugreport <approved-output-path>
```

A full bug report can contain sensitive device information. Generate one only when needed, tell the user first, store it outside Git, and delete or retain it according to the user's direction.

### 22.9 Device acceptance sequence

Run this sequence on every supported phone/keyboard combination before declaring it compatible:

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

### 22.10 Release signing configuration

Use one long-lived release key for all privately distributed versions. Create it once outside the repository with `keytool`, record its certificate fingerprint, back it up securely in at least two controlled locations, and never regenerate it for later versions. Losing the key prevents seamless upgrades on devices that have an earlier release installed.

Keep these outside Git:

- the `.jks` or `.keystore` file,
- store password,
- key password,
- key alias if treated as sensitive,
- local signing-properties file.

The ignored local properties file may reference the external keystore, but Gradle and scripts must fail with a clear message when signing inputs are missing. Do not silently sign a distributable build with the debug key.

Release builds should additionally enable appropriate shrinking/obfuscation only after verifying that Compose, Accessibility Service declarations, Gemini serialization, and encrypted-storage code work in the minified build. Preserve mapping files for every distributed release.

### 22.11 Reproducible APK packaging

Before packaging:

1. Ensure the worktree is understood and intentional; do not include unrelated changes accidentally.
2. Update `versionCode`, `versionName`, and `CHANGELOG.md`.
3. Run the secret/logging audit.
4. Run unit tests and lint.
5. Run connected tests when a suitable phone is available.
6. Build the signed release APK.

Recommended command sequence:

```powershell
./gradlew clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

If release-specific unit tasks differ in the final Gradle configuration, use the equivalent verified tasks and document them in `scripts/build-release.ps1`.

Verify the produced APK before copying it:

```powershell
apksigner verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
aapt dump badging .\app\build\outputs\apk\release\app-release.apk
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Confirm:

- package name is `com.whispertype.android`,
- version code and version name are correct,
- minimum and target SDK values are correct,
- APK is signed by the expected release certificate,
- no debug certificate is present,
- the APK contains no API key, signing secret, private transcript, or local machine path,
- the release APK can be installed and launched.

Create a versioned distribution directory outside Gradle's generated output, for example:

```text
dist/
└── 0.1.0/
    ├── WhisperType-Android-0.1.0.apk
    ├── WhisperType-Android-0.1.0.apk.sha256
    ├── RELEASE_NOTES.md
    ├── INSTALL.md
    └── mapping.txt
```

`dist/` should be ignored by Git unless the project explicitly decides to version release artifacts. The checksum file must contain the exact filename and SHA-256 value. `INSTALL.md` must cover both opening the APK directly on a phone and installing it with ADB.

### 22.12 Release-candidate installation and upgrade verification

When the release candidate is ready, tell the user that a physical phone is required and ask them to connect, unlock, and authorize it. Then:

1. Verify the connected device serial and model.
2. Install the signed release candidate with `adb install -r`.
3. Launch it and complete the full smoke test.
4. Confirm the release build connects to Gemini and records successfully without debug-only configuration.
5. Confirm Accessibility Service discovery and overlay behavior in the minified release build.
6. Confirm the foreground-service notification and microphone privacy indicator.
7. Confirm the app version in Android package details.

Perform two separate install paths:

- Clean install: on a device or test profile without WhisperType installed, install the APK and complete onboarding.
- Upgrade install: install the previous signed release, configure it, then install the new APK with `-r`. Confirm encrypted settings, API key state, history preference, and Accessibility behavior remain valid or migrate as documented.

Also verify rollback expectations. Android normally blocks downgrades by version code; `-d` is for development diagnosis only and is not part of user-facing installation guidance.

### 22.13 Distribution to other personal devices

The final deliverable for each private release is the signed APK distribution directory, not the temporary APK under `app/build/outputs`.

To distribute:

1. Copy `WhisperType-Android-<version>.apk` and its `.sha256` file to the target device using a trusted local method.
2. Optionally verify the checksum on the receiving computer before transfer.
3. Open the APK on the target phone and allow installs from that file-manager/browser source when Android requests it.
4. Install and open WhisperType.
5. Complete microphone, notification, Accessibility, API-key, and compatibility setup on that device.

Alternatively, with an authorized USB-connected device:

```powershell
adb -s <serial> install -r .\dist\<version>\WhisperType-Android-<version>.apk
```

Each other device stores its own encrypted Gemini API key and permissions. App-private encrypted data must not be copied between phones manually.

Do not distribute:

- debug APKs,
- unsigned APKs,
- the release keystore,
- signing properties or passwords,
- mapping files to ordinary recipients,
- logs or diagnostics,
- APKs whose checksum/signature was not verified.

### 22.14 Deployment scripts and documentation deliverables

Implement and maintain these scripts:

- `scripts/adb-preflight.ps1`: locate ADB, list devices, require an explicit serial when multiple targets exist, and print non-sensitive device/build information.
- `scripts/install-debug.ps1`: build/install the debug APK and optionally launch it.
- `scripts/collect-diagnostics.ps1`: collect privacy-safe scoped logs and dumps into an ignored timestamped directory.
- `scripts/build-release.ps1`: validate version/signing inputs, run release checks, build, verify signature, generate checksum, and create the versioned `dist` directory.
- `scripts/install-release.ps1`: verify the APK/checksum, select one authorized device, install with `-r`, and launch the app.

Scripts must:

- stop on errors,
- quote Windows paths safely,
- never default to every connected device,
- never print secrets,
- never uninstall or clear data without an explicit switch and confirmation,
- display the exact artifact and target device before installation,
- return non-zero exit codes on build, signature, checksum, install, or launch failure.

Update `README.md`, `docs/TESTING.md`, `docs/DEVICE_COMPATIBILITY.md`, `docs/RELEASE_PROCESS.md`, `docs/TROUBLESHOOTING.md`, and `docs/USER_SETUP.md` so the documented commands match the scripts exactly.

### 22.15 Deployment and packaging completion gate

Deployment is complete only when:

- ADB preflight works with zero, one, unauthorized, offline, and multiple-device states.
- The implementation agent pauses and asks the user to connect/unlock/authorize the phone at the first real-device gate.
- Debug APK installation, launch, reinstall, and scoped diagnostics work on the target phone.
- Accessibility and permission steps are completed through supported Android UI flows.
- The physical-device acceptance sequence passes on at least one Pixel and one Samsung target before a stable release, unless the release notes explicitly mark a narrower prototype scope.
- A signed, non-debug APK is produced with the expected application ID and version.
- `apksigner` verification succeeds against the recorded release certificate.
- Clean-install and upgrade-install tests pass.
- The versioned distribution directory contains the APK, SHA-256 checksum, install guide, and release notes.
- The APK installs on at least one additional personal device or is explicitly marked as awaiting that device test.
- The release keystore and credentials remain outside the repository and are backed up securely.
- No secret, transcript, audio, unique device identifier, or sensitive diagnostic artifact is included in Git or the distribution package.

## 23. Definition of done

The first stable private release is complete only when:

- WhisperType Android is fully standalone.
- SwiftKey/Gboard/Samsung Keyboard remains active before, during, and after dictation.
- The dock appears only for eligible text fields with a stable docked keyboard.
- The dock is customizable within safe keyboard-boundary limits.
- Tapping the dock morphs into a keyboard-covering voice panel.
- The panel does not steal focus or resize the app.
- Tap-to-start and tap-to-stop work reliably.
- Waveform-only UI works in light/dark/reduced-motion modes.
- Gemini Live streams audio correctly.
- Audio queues remain bounded and drain before activity end.
- Cleaned transcript fallback to raw transcript works.
- Hinglish never inserts Devanagari.
- Invalid output is rejected.
- Exactly one insertion or explicit copy fallback occurs.
- Focus changes never cause stale insertion.
- Secure fields never activate WhisperType.
- History is disabled by default.
- Optional history is encrypted and removable.
- API keys are protected and absent from logs/backups.
- Accessibility and microphone setup are clearly documented.
- Signed APK installation and upgrade are documented.
- Pixel and Samsung test matrices pass.
- The technical spike assumptions have been verified on target devices.

---

# User Setup Guide

Save this section as:

`C:\Users\Kunal\Desktop\Projects\WhisperType-Android\docs\USER_SETUP.md`

## WhisperType Android setup

WhisperType Android keeps your existing SwiftKey, Gboard, or Samsung Keyboard active. It does not replace your keyboard.

When you focus a normal text field, a small WhisperType microphone control appears attached to the keyboard boundary. Tap it to dictate. The keyboard is visually replaced by a WhisperType voice panel while you speak, then returns after the text is inserted.

## Requirements

- Android 14 or newer.
- A supported Pixel or Samsung device.
- Gboard, SwiftKey, or Samsung Keyboard in standard docked mode.
- Internet access for Gemini Live.
- A Gemini API key.
- Permission to use the microphone.
- WhisperType Accessibility Service enabled.

## Install the APK

1. Copy the signed WhisperType APK to your phone.
2. Open the APK.
3. If Android blocks installation, open the displayed settings page and allow the application used to open the APK to install unknown apps.
4. Return to the APK and install it.
5. Open WhisperType.

For development installation from a computer:

```powershell
adb install -r WhisperType-Android-0.1.0-release.apk
```

## Grant microphone permission

1. Open WhisperType.
2. Tap `Allow microphone`.
3. Choose `While using the app` if Android presents that option.
4. Return to WhisperType.
5. Confirm that microphone status says `Ready`.

WhisperType does not record continuously. The microphone is used only during an active dictation session.

## Grant notification permission

WhisperType shows a recording notification while the microphone is active.

1. When Android asks for notifications, tap `Allow`.
2. If you previously denied it, open:

   `Settings → Apps → WhisperType → Notifications`

3. Enable notifications.

The notification includes Stop and Cancel actions while recording.

## Enable Accessibility access

Accessibility access is required so WhisperType can:

- detect when a text field is active,
- detect the active keyboard’s position,
- place the docked control next to the keyboard,
- insert the final text at the cursor.

WhisperType does not use this permission to read or store unrelated screen content.

### Pixel / stock Android

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Downloaded apps` or `Installed apps`.
4. Tap `WhisperType`.
5. Turn on `WhisperType`.
6. Read the disclosure.
7. Tap `Allow`.
8. Return to WhisperType.

### Samsung Galaxy

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Installed apps`.
4. Tap `WhisperType`.
5. Turn on the service.
6. Confirm the disclosure.
7. Return to WhisperType.

The exact labels may vary slightly by Android or One UI version.

## Keep your normal keyboard selected

WhisperType does not need to become your default keyboard.

To verify your keyboard:

### Pixel / stock Android

`Settings → System → Keyboard → On-screen keyboard`

### Samsung

`Settings → General management → Keyboard list and default`

Keep SwiftKey, Gboard, or Samsung Keyboard selected as your default.

## Configure the Gemini API key

1. Open WhisperType.
2. Open `Settings`.
3. Tap `Gemini API key`.
4. Paste or type your key.
5. Tap `Save`.
6. Tap `Test connection`.
7. Confirm that the test succeeds.

The key is encrypted with Android Keystore-backed storage. It is not saved in normal app preferences, history, logs, backups, or the clipboard.

## Choose speech mode

Open:

`WhisperType → Settings → Speech mode`

Choose:

- `English`
- `Hinglish`

Hinglish supports natural English/Hindi code-switching but always produces Latin-script output. Devanagari is rejected.

## Customize the dock

Open:

`WhisperType → Settings → Dock`

Available controls:

- Position: Left, Center, Right.
- Size: Compact, Standard, Large.
- Vertical overlap: Mostly over keyboard or Mostly above keyboard.
- Opacity.
- Theme: System, Light, Dark.
- Accent color.
- Haptic feedback.
- Optional start/stop sound.
- Elapsed-time visibility.
- Cancel button side.

The control cannot be moved freely over arbitrary app content. It stays attached to safe keyboard-relative positions.

## Run the compatibility test

1. Open WhisperType.
2. Tap `Compatibility test`.
3. Focus the sample text field.
4. Wait for your normal keyboard to appear.
5. Confirm that the WhisperType dock appears at the keyboard boundary.
6. Tap the dock.
7. Confirm that the voice panel covers the keyboard.
8. Tap Stop.
9. Confirm that the sample text field receives the result.
10. Confirm that the original keyboard returns.

If the test fails, use `Settings → Troubleshooting` and export diagnostics. Diagnostics contain timing and error codes, not transcripts, audio, API keys, or editor text.

## Daily use

1. Open an app such as Messages, Gmail, Chrome, Notes, or another supported application.
2. Tap inside a normal text field.
3. Wait for your normal keyboard to appear.
4. Tap the WhisperType docked microphone.
5. The keyboard area changes into the WhisperType voice panel.
6. Speak naturally.
7. Tap `Stop`.
8. Wait while WhisperType finalizes the transcript.
9. WhisperType inserts the result at the current cursor or replaces the selected text.
10. The normal keyboard returns.

The voice panel does not display transcript text. It displays the waveform, recording state, and optional elapsed time.

## Cancel a dictation

You can cancel by:

- tapping `Cancel` in the voice panel,
- tapping `Cancel` in the recording notification,
- pressing the Android Back button while the voice panel is active,
- locking the phone,
- switching to another app or field.

Cancellation discards the pending result and does not insert text.

## If direct insertion is unavailable

Some fields do not expose a safe editable connection.

Examples include:

- password fields,
- payment fields,
- secure PIN fields,
- private browsing fields,
- unusual WebViews,
- canvas-based editors,
- remote desktop applications,
- unsupported floating or split keyboards.

In these cases:

1. WhisperType finishes the transcription.
2. It does not insert into the field.
3. Tap `Copy`.
4. Return to the text field.
5. Paste using SwiftKey, Gboard, or Samsung Keyboard.

WhisperType never silently pastes into an uncertain field.

## Secure fields

WhisperType intentionally does not appear in:

- password inputs,
- PIN inputs,
- payment fields,
- banking authentication fields,
- secure login fields.

This protects sensitive information and avoids accidental recording.

## Optional history

History is disabled by default.

To enable it:

1. Open `Settings`.
2. Tap `Privacy and history`.
3. Enable `Local history`.
4. Choose a retention period.

When enabled, history is encrypted and stored locally. Audio is never stored.

To remove it:

`Settings → Privacy and history → Clear all history`

## Troubleshooting

### The dock does not appear

Check:

1. WhisperType Accessibility Service is enabled.
2. Microphone permission is enabled.
3. Gemini API key is saved.
4. The text field is not secure.
5. The keyboard is fully visible.
6. The keyboard is a supported docked keyboard.
7. WhisperType is not disabled for the current app.
8. Android has not stopped the Accessibility Service.

Reopen:

`Settings → Accessibility → Installed/Downloaded apps → WhisperType`

### The dock disappears

The dock hides when:

- the keyboard closes,
- the text field loses focus,
- the screen locks,
- an unsupported keyboard layout is detected,
- the current field is secure,
- the Accessibility Service disconnects.

This is expected behavior.

### The voice panel does not start

Check:

1. Microphone permission.
2. Notification permission.
3. Gemini API key.
4. Network connection.
5. Whether another app is using the microphone.
6. Whether the recording notification appears.

### Text was copied instead of inserted

The application likely did not expose a reliable input connection. Tap `Copy`, return to the field, and paste manually.

### Samsung stops showing the dock

Open:

`Settings → Accessibility → Installed apps → WhisperType`

Confirm the service remains enabled.

If the device has aggressive battery management, open:

`Settings → Apps → WhisperType → Battery`

Use the least restrictive setting that Samsung provides for the installed version. Do not disable battery protections unless WhisperType documentation specifically identifies the device as requiring it.

### Gemini connection fails

Check:

- API key is valid.
- The key has Gemini API access.
- The phone has internet access.
- Device date and time are correct.
- No VPN, firewall, or private DNS is blocking the connection.

WhisperType never displays or logs the full authenticated Gemini connection URL.

## Disable Accessibility access

### Pixel / stock Android

`Settings → Accessibility → Downloaded/Installed apps → WhisperType → Off`

### Samsung

`Settings → Accessibility → Installed apps → WhisperType → Off`

Disabling the service immediately hides the dock and cancels any active recording.

## Delete the Gemini API key

Open:

`WhisperType → Settings → Gemini API key → Delete key`

Then confirm deletion.

## Uninstall WhisperType

1. Open Android Settings.
2. Tap `Apps`.
3. Tap `WhisperType`.
4. Tap `Uninstall`.
5. Confirm.

Before uninstalling, optionally:

1. Disable Accessibility access.
2. Delete the Gemini API key.
3. Clear local history.
4. Revoke microphone and notification permissions.

## Privacy summary

WhisperType:

- records only after you tap the dock,
- streams audio directly to Gemini Live,
- does not use a WhisperType cloud account,
- does not use a backend server,
- does not store audio,
- does not store history unless you enable it,
- does not read the clipboard,
- does not log complete transcripts,
- does not log API keys,
- does not operate in secure fields,
- requires Accessibility access only for field detection, keyboard positioning, and final text insertion.
</proposed_plan>
