# WhisperType Android
## Product Requirements and Detailed Execution Plan

**Document status:** Authoritative rebuild specification  
**Version:** 1.0  
**Date:** 2026-08-04  
**Application ID:** `com.whispertype.android`  
**Initial product version:** `0.1.0`  
**First physical target:** Samsung phone with SwiftKey

---

## 1. Executive Summary

WhisperType is a standalone Android voice-dictation companion. It keeps the user's existing keyboard selected, presents a small Wispr Flow-style microphone bubble above application content, records only after an explicit tap, sends audio directly to Gemini Live, extracts a validated transcript, and inserts it into the currently focused editor.

The current implementation is not the foundation for release. It contains contradictory overlay policies, unverified accessibility behavior, fragile insertion paths, incorrect lifecycle ownership, an unproven Gemini protocol, and tests that can pass without proving that a visible overlay exists on a phone. This document supersedes implementation decisions that conflict with the requirements below and defines a clean rebuild rather than another patch cycle.

The first successful milestone is deliberately narrow:

> On a Samsung phone using SwiftKey, a normal text field shows a touchable WhisperType bubble, tapping it inserts static test text into the correct field, and the keyboard remains selected.

Gemini, audio, history, and visual polish are added only after that feasibility gate passes.

---

## 2. Product Decisions

### 2.1 Authoritative decisions

- Rebuild the runtime architecture cleanly instead of repairing the existing coordinator/overlay/service design in place.
- Use a close behavioral and visual replica of Wispr Flow's observable interaction model.
- Implement original WhisperType code, branding, assets, strings, and visual details.
- Do not copy proprietary Wispr source code, assets, package identifiers, analytics, or undocumented secrets.
- Use a persistent, small `TYPE_ACCESSIBILITY_OVERLAY` bubble, modeled on the observed reference behavior.
- Keep the user's selected SwiftKey, Gboard, or Samsung Keyboard active. WhisperType is not an IME in v1.
- Revalidate Gemini Live against current official documentation before finalizing the wire contract.
- Target Android 14 and newer, with Samsung SwiftKey as the first acceptance combination.
- Keep history disabled by default and do not store audio.

### 2.2 Superseded decisions

The original plan required a microphone docked to the upper edge of the keyboard and prohibited any floating bubble. The product direction is now explicitly Wispr-style: a small persistent bubble positioned at the screen edge and shown only when an eligible editor/keyboard context exists. The old keyboard-bound fallback geometry and its associated implementation are not to be retained.

This does not authorize an unrestricted overlay. The bubble must remain small, eligible-context-gated, non-focusable, recoverable, and tested against accidental obstruction of application controls.

---

## 3. Problem Statement

Typing on a phone is slow and error-prone, especially for long messages and English/Hinglish speech. The user wants voice dictation without replacing or switching away from their preferred keyboard.

The current prototype has not demonstrated the basic product loop on a physical phone. Reported symptoms include an invisible overlay, unclickable controls, crashes, and no confirmed text insertion. The rebuild must prove each platform boundary independently before connecting them together.

---

## 4. Goals and Non-Goals

### 4.1 Goals

- Show a reliable, touchable dictation bubble over eligible editing contexts.
- Preserve the selected third-party keyboard and editor focus.
- Start and stop recording explicitly from the bubble/panel.
- Use a foreground microphone service with Android-required disclosure.
- Send live audio to Gemini Live from the device.
- Select a valid cleaned transcript, falling back to a valid raw transcript.
- Insert exactly once into the original target field.
- Refuse unsafe or stale insertion.
- Provide explicit Copy fallback when direct insertion cannot be verified.
- Provide clear onboarding, permission recovery, diagnostics, and failure states.
- Support English and Latin-script Hinglish.
- Produce repeatable automated and physical-device evidence.

### 4.2 Non-goals for v1

- Replacing the user's keyboard or implementing a full IME.
- Modifying SwiftKey, Gboard, or Samsung Keyboard internals.
- Continuous background listening.
- Automatic keyboard switching.
- Storing raw audio.
- Live transcript display inside the recording panel.
- Cloud transcript history by default.
- Floating/split/handwriting keyboard support unless separately validated.
- Multi-model Gemini settings exposed to ordinary users.
- Copying Wispr Flow's proprietary code or assets.

---

## 5. User Experience Requirements

### 5.1 Core journey

1. User opens WhisperType and completes disclosure, permissions, Accessibility setup, API-key setup, and demo insertion.
2. User opens any supported app and focuses an eligible text field.
3. WhisperType shows a small bubble in the reference-inspired location.
4. User taps the bubble.
5. WhisperType captures the current editor target and starts the foreground microphone service.
6. The bubble changes to the recording panel with waveform, stop, cancel, and optional elapsed time.
7. User speaks naturally in English or Latin-script Hinglish.
8. User taps Stop.
9. WhisperType drains accepted audio, closes the Gemini turn exactly once, validates the candidates, and inserts one result.
10. The recording UI collapses and the user's keyboard remains available.
11. If insertion cannot be verified, WhisperType presents Copy and Dismiss. It never copies automatically.

### 5.2 Bubble state

The idle bubble must:

- Be small and visually unobtrusive.
- Use an original WhisperType microphone icon.
- Have at least a 48 dp touch target.
- Be non-focusable and touchable.
- Have a visible pressed/disabled state.
- Expose an accessibility label such as `Start WhisperType dictation`.
- Be shown only when all eligibility conditions are satisfied.
- Hide when the editor, keyboard, service, permission, or security state becomes invalid.

### 5.3 Recording panel

The panel must:

- Clearly communicate Starting, Listening, and Finalizing.
- Display a responsive waveform but no transcript text.
- Provide separate Stop and Cancel actions.
- Prevent accidental taps from reaching covered keyboard content where the panel overlaps it.
- Respect dark mode, font scaling, TalkBack, and reduced-motion settings.
- Provide a deterministic error state with a functioning dismissal/recovery action.

### 5.4 Safety behavior

Never show or start dictation in password, PIN, payment, authentication, secure-browser, private, or otherwise uncertain fields. If security classification fails or cannot be established, fail closed.

Never insert into a field that changed after session start. Focus change must invalidate the session, including two fields in the same package/window with missing resource IDs.

---

## 6. Functional Requirements

### FR-1: Onboarding

- Explain Accessibility's purpose accurately.
- Request microphone and notification permissions through Android UI.
- Open Accessibility settings and refresh status automatically on return.
- Accept, replace, delete, and test a masked Gemini API key.
- Require a real editable test field for the demo; never fabricate a target token.
- Verify static insertion before onboarding completion.
- Verify the existing keyboard remains selected.
- Permit configuration and troubleshooting even when setup is incomplete.
- Present a clear recovery action for every denied or revoked permission.

### FR-2: Eligibility

The bubble is eligible only when:

- Accessibility service is connected.
- A supported editable field is focused.
- The field is not secure or uncertain.
- A supported soft keyboard context is visible.
- Microphone permission is granted.
- A Gemini key is configured.
- The current app is not disabled by the user.
- No dictation session is active.

### FR-3: Overlay

- Use `TYPE_ACCESSIBILITY_OVERLAY`.
- Use a persistent bubble attachment while the service is alive.
- Use `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCH_MODAL`, and in-screen layout as appropriate.
- Handle add/update/remove failures explicitly; never mark a failed window as attached.
- Recreate or retry after recoverable display/window failures.
- Avoid application-control obstruction and hide on unsupported/ambiguous layouts.

### FR-4: Dictation service

- Own exactly one active session.
- Start only from explicit user interaction.
- Promote to microphone foreground mode before starting `AudioRecord`.
- Show a recording notification with Stop, Cancel, and Open actions.
- Carry and validate the canonical session ID on every command/result.
- Stop cleanly on cancellation, lock, permission revocation, focus change, app switch, microphone loss, and network failure.
- Release audio, WebSocket, notification, and overlay-related resources idempotently.

### FR-5: Audio

- Capture mono PCM16 using the officially supported Gemini sample rate.
- Frame audio into exact 20 ms chunks, including partial-read handling.
- Use one bounded transmission queue with measurable capacity and overflow behavior.
- Drain accepted chunks before sending one activity-end boundary.
- Never silently drop chunks.
- Expose only throttled amplitude to UI; never raw audio.

### FR-6: Gemini Live

- Select and document one current official Live model.
- Await setup acknowledgement before audio transmission.
- Assert exact setup/audio/transcription/activity-end ordering in fake-server tests.
- Receive raw and cleaned candidates in one session.
- Await turn completion with a bounded timeout.
- Do not retry after audio transmission begins.
- Redact API keys, authenticated URLs, transcripts, and raw audio from logs.

### FR-7: Transcript selection

Selection order:

1. Valid cleaned candidate.
2. Valid raw candidate.
3. No insertion and an actionable failure or Copy state if a valid result exists but insertion fails.

Reject empty, punctuation-only, corrupted, model-preamble, implausibly expanded, duplicated, Devanagari-in-Hinglish, and otherwise invalid candidates. Preserve legitimate URLs, emails, identifiers, numbers, punctuation, short phrases, and natural Latin-script code-switching.

### FR-8: Insertion

- Reacquire the current supported accessibility input connection.
- Validate target package, display, window/editor identity, generation, selection context where observable, secure state, lock state, cancellation state, and result-consumed state.
- Commit once using `commitText` or the verified supported equivalent.
- Verify the result where possible.
- Never inject synthetic uncontrolled keystrokes or silently paste.
- Never automatically switch keyboards.

### FR-9: Copy fallback

- Show `Copy`, not `Copied`, before user action.
- Copy only after explicit user tap.
- Mark clipboard content sensitive where supported.
- Show confirmation after successful copy.
- Clear the in-memory candidate after copy or dismiss.
- Make error and fallback panels independently dismissible.

### FR-10: Settings and privacy

- Store the API key only in Keystore-backed encrypted app-private storage.
- Exclude secrets and history from backup.
- Keep history off by default and do not initialize transcript storage while disabled.
- Expose only settings that have working runtime consumers.
- Provide compatibility and diagnostics screens through reachable navigation.

---

## 7. Architecture And Module Boundaries

Keep one Android application module initially, but enforce logical boundaries. Do not create Gradle modules until contracts stabilize.

### `core-model`

Pure Kotlin session IDs, states, commands, failures, target snapshots, language mode, result types, and metrics.

### `core-state`

The sole reducer/state owner. It serializes commands and rejects stale events.

### `platform-accessibility`

Accessibility service, editor tracking, security classification, input-method connection, target capture, and insertion transaction. No Gemini, audio, history, or secret storage.

### `platform-overlay`

Persistent window host, lifecycle owners, bubble/panel geometry, Compose rendering, touch policy, and overlay watchdog. No dictation orchestration.

### `audio-engine`

AudioRecord abstraction, exact chunking, bounded queue, drain semantics, waveform meter, and audio diagnostics.

### `gemini-live`

Official protocol, WebSocket lifecycle, setup acknowledgement, transcript events, end boundary, timeout, error mapping, and redaction.

### `dictation-service`

Foreground service session owner, notification, audio/Gemini orchestration, cancellation, final candidate lifecycle, and resource cleanup.

### `data-secrets`, `data-settings`, and `data-history`

Independent interfaces and repositories. ViewModels receive interfaces; they do not construct storage objects directly.

### `feature-onboarding` and `feature-settings`

Compose flows, recovery actions, status refresh, and compatibility test entry points.

### `test-host`

Instrumented test editor host and physical-device test controls. It must not be shipped as ordinary product UI.

---

## 8. State Machine

```text
Unavailable
  -> Idle
  -> Starting
  -> Listening
  -> Finalizing
  -> Inserting
  -> Success

Starting/Listening/Finalizing/Inserting
  -> Cancelled
  -> Error

Inserting
  -> CopyAvailable
  -> Error

CopyAvailable
  -> Idle after Copy or Dismiss
Error
  -> Idle after Dismiss or recovery action
```

Rules:

- One active session globally.
- One canonical `sessionId` from target capture through service, Gemini, insertion, notification, and terminal result.
- Every asynchronous event is ignored if it belongs to an old session.
- `Finalizing` is a real production state, not merely a tested enum.
- Cancellation establishes a stale-session barrier before resource cleanup.
- A terminal result is consumed at most once.

---

## 9. Delivery Phases And Gates

### Phase 0: Scope and reference evidence

Deliver the behavior matrix and clean-room decision record. No production code changes beyond scaffolding.

**Exit gate:** unresolved reference assumptions are listed and assigned to observation or implementation spikes.

### Phase 1: Clean runtime foundation

Replace the old runtime ownership, establish contracts, configure build/test variants, and remove contradictory fallback behavior.

**Exit gate:** debug build, lint, pure tests, and source privacy audit pass.

### Phase 2: Samsung SwiftKey overlay/input spike

Implement persistent bubble, editor target capture, static insertion, target invalidation, and overlay recovery.

**Exit gate:** physical Samsung SwiftKey acceptance passes. If it fails, stop and reassess architecture before Gemini work.

### Phase 3: UI shell

Implement the close reference-inspired bubble, recording panel, error, copy, accessibility semantics, theme, and reduced-motion states.

**Exit gate:** Compose/UI tests prove every visible control exists, is discoverable, and is clickable.

### Phase 4: Foreground service and audio

Implement acknowledged foreground promotion, notification actions, exact audio framing, bounded transport queue, waveform, and cleanup.

**Exit gate:** fake lifecycle/audio tests and Samsung microphone smoke test pass.

### Phase 5: Gemini Live

Revalidate official protocol, implement client, fake server, transcript assembly, and candidate validation.

**Exit gate:** contract suite passes and one real short utterance succeeds on Samsung.

### Phase 6: End-to-end insertion and fallback

Connect validated results to insertion, verification, Copy fallback, and history event sink.

**Exit gate:** exactly-one insertion, selection replacement, stale-target rejection, and explicit fallback pass.

### Phase 7: Onboarding/settings/recovery

Finish prerequisite-driven onboarding, settings navigation, permission recovery, diagnostics, and optional history.

**Exit gate:** onboarding cannot complete falsely and every exposed control works.

### Phase 8: Compatibility and release

Run the full Samsung SwiftKey matrix, then expand keyboards/devices, produce signed APK, test upgrade, and document limitations.

**Exit gate:** release checklist is complete and all claims are backed by recorded evidence.

---

## 10. Sub-Agent Execution Protocol

Each sub-agent must work on one ownership boundary and must not edit overlapping files.

Required assignment format:

```text
Objective:
Allowed files:
Forbidden files:
Input contracts:
Output contracts:
Tests required:
Device requirements:
Exit criteria:
```

Required report format:

```text
Changed files:
Behavioral decisions:
Tests run:
Failures:
Known limitations:
Integration risks:
Required follow-up:
```

The lead integration agent owns Gradle, manifest, version catalog, application wiring, shared contracts, merge conflict resolution, build verification, physical-device checkpoints, and release claims.

No agent may:

- Commit secrets, API keys, keystores, real transcripts, raw audio, or device logs containing user data.
- Claim physical-device success from unit tests or an installed APK alone.
- Use hidden ADB workarounds to grant Accessibility access.
- Modify another workstream's files without explicit integration ownership.
- Add a setting without a runtime implementation and test.
- Replace a failed device gate with an emulator result.

---

## 11. Test Plan

### 11.1 Test evidence labels

Every result must be labeled one of:

- `PASS`: required behavior observed and asserted.
- `FAIL`: required behavior did not pass.
- `BLOCKED`: required environment or permission unavailable.
- `NOT RUN`: not attempted.
- `INCONCLUSIVE`: evidence insufficient.

“Build succeeded” and “APK installed” are never overlay or insertion evidence.

### 11.2 Host-only commands

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Host-only validation covers pure logic, protocol fixtures, audio framing, security scans, settings, Compose semantics, and APK metadata. It cannot prove Accessibility overlays, OEM keyboard geometry, foreground microphone policy, or physical insertion.

### 11.3 Emulator commands

```bash
./gradlew :app:connectedDebugAndroidTest
adb devices -l
adb -s <serial> shell am start -n com.whispertype.android/.MainActivity
```

Use emulators for Activity/UI, permissions, rotation, notifications, fake Gemini, and test-host behavior. Label all emulator results separately. Do not use an emulator as the release compatibility gate.

### 11.4 Samsung ADB preflight

Before the first physical-device task, the user must connect and unlock the Samsung phone, enable Developer Options and USB debugging, use a data-capable connection, and approve the USB debugging prompt.

```bash
java -version
./gradlew --version
adb version
adb start-server
adb devices -l
```

Handle `no device`, `unauthorized`, `offline`, and multiple-device states explicitly. Never install to an unspecified target.

Capture a privacy-safe baseline:

```bash
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell settings get secure default_input_method
```

Install and verify the exact build:

```bash
./gradlew :app:assembleDebug
adb -s <serial> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell dumpsys package com.whispertype.android
adb -s <serial> shell pm path com.whispertype.android
adb -s <serial> shell am start -n com.whispertype.android/.MainActivity
```

Never report installation success without confirming package version and APK path through `dumpsys`/`pm path`.

### 11.5 Device diagnostics

```bash
adb -s <serial> logcat -c
adb -s <serial> shell pidof -s com.whispertype.android
adb -s <serial> logcat --pid=<pid>
adb -s <serial> shell dumpsys accessibility
adb -s <serial> shell dumpsys window
adb -s <serial> shell dumpsys activity services com.whispertype.android
```

Logs must use stable non-sensitive tags. Do not collect full bug reports unless necessary and explicitly approved; they may contain private device information.

### 11.6 Manual Samsung SwiftKey acceptance

The first physical smoke test must cover:

1. Install and verify the intended version.
2. Grant microphone and notification permissions normally.
3. Enable Accessibility normally.
4. Confirm SwiftKey remains the selected keyboard.
5. Focus a normal single-line field and observe the bubble.
6. Tap the bubble and observe panel, notification, and microphone privacy indicator.
7. Speak a short English phrase and stop.
8. Confirm exactly one insertion.
9. Repeat with Latin-script Hinglish.
10. Replace a selected range.
11. Cancel and confirm no insertion.
12. Change focus during recording and finalization.
13. Test password and PIN fields.
14. Rotate idle and recording states.
15. Switch apps, lock/unlock, and test microphone interruption.
16. Switch keyboards and repeat the smoke test.
17. Run twenty consecutive sessions.
18. Record build, Android, device, SwiftKey version, result, and reproduction details.

### 11.7 Stability and crash budget

For a release candidate:

- Zero crashes in onboarding smoke test.
- Zero overlay crashes across 50 keyboard show/hide cycles.
- Zero duplicate insertions across 20 consecutive sessions.
- Zero wrong-field insertions across focus-switch tests.
- Zero leaked active microphone sessions after terminal states.
- Zero stuck error/copy panels after dismissal.
- Zero secret/transcript/audio violations in logs and artifacts.

Any failure in these categories blocks release until reproduced, fixed, and retested.

---

## 12. Security, Privacy, and Compliance

- Accessibility disclosure must accurately describe field detection, overlay interaction, and insertion.
- Use least-privilege service configuration.
- Do not request key-event filtering without a proven requirement.
- API keys use Android Keystore-backed AES-GCM encryption with random IVs.
- Secret files are app-private and excluded from backup.
- Invalid/corrupt ciphertext has a deterministic recovery path.
- No API key, authenticated URL, transcript, editor content, AccessibilityNode tree, clipboard content, or raw audio appears in logs.
- Clipboard writes are explicit and sensitive-marked.
- History is encrypted, opt-in, retention-controlled, and absent when disabled.
- Diagnostics contain typed failures and aggregate timing only.

---

## 13. Release Criteria

A signed private APK may be produced only when:

- The debug build, unit tests, lint, and instrumentation suite pass.
- The Samsung SwiftKey overlay/input gate passes on a real phone.
- Foreground microphone startup is physically verified.
- The official Gemini Live contract is documented and tested.
- One real end-to-end dictation succeeds.
- English, Hinglish, cancellation, selection replacement, secure fields, and stale targets pass.
- Copy fallback and error dismissal work.
- Secret and forbidden-log audits pass.
- Installation and upgrade are verified using the expected package/signature.
- Device compatibility documentation records actual evidence, not assumptions.
- Known limitations are documented.

The first release should remain a private prototype until the Samsung SwiftKey acceptance sequence passes. Pixel, Samsung Keyboard, and Gboard support should not be claimed before their own matrix rows pass.

---

## 14. Immediate Next Steps

1. Create a clean rebuild branch from the latest verified commit.
2. Freeze the current source as audit evidence; do not patch the old runtime.
3. Implement the pure core contracts and reducer.
4. Implement the persistent overlay/static insertion spike.
5. Build and install the first debug APK on the Samsung phone.
6. Ask the user to enable Accessibility, grant permissions, select SwiftKey, and perform the visible smoke test.
7. Do not begin Gemini/audio integration until the overlay and static insertion gate passes.
8. Revalidate Gemini Live documentation and lock the protocol before implementing the client.

---

## 15. Appendix: Current Known Risks

- Android OEMs may differ in how Accessibility overlays are layered above keyboards.
- Samsung and SwiftKey may expose different accessibility window/editor metadata.
- Foreground microphone startup from an accessibility overlay is platform- and OEM-sensitive.
- WebView and custom editors may not expose a usable accessibility input connection.
- Gemini Live protocol/model availability may change and must be pinned to tested documentation.
- A reference application's behavior is evidence, not a guarantee that the same implementation is permitted or stable on all Android versions.
- No physical-device result currently exists for this repository; all compatibility claims remain unverified until the Samsung SwiftKey gate is executed.

---

## 16. Detailed Technical Implementation Plan

This section is the implementation contract for the rebuild. It is intentionally more specific than the delivery phases above. A sub-agent may implement only the boundary assigned to it and must satisfy the associated contract tests before integration.

### 16.1 Repository and build foundation

Keep the initial project as one `app` module with strict package boundaries. The first rebuild commit must establish:

- Kotlin/JVM 17 and the pinned Android Gradle Plugin/toolchain already supported by the workspace.
- `minSdk = 34`, current stable compile/target SDK, and a documented version catalog.
- Debug and release variants with the same runtime behavior except signing and diagnostics configuration.
- Explicit `debugImplementation` dependencies for test-only tooling.
- Unit-test, instrumentation-test, Compose UI-test, and screenshot-test source sets where practical.
- Static privacy audit that scans source, resources, generated APK strings, and logs.
- No production dependency on the desktop WhisperType project.
- No API keys, keystores, real transcripts, audio, generated APKs, or device logs in Git.

Lead-owned files:

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
WhisperTypeApplication.kt
```

The lead must not permit workstreams to edit these files independently. Shared dependency or manifest changes are submitted as integration requests with the exact reason and test impact.

### 16.2 Runtime dependency graph

The dependency direction must be one-way:

```text
UI features -> application interfaces -> platform/data adapters
Dictation service -> audio engine + Gemini client + target/insertion interface
Accessibility service -> overlay host + target/insertion interface + dictation command interface
Pure core <- all adapters
```

Forbidden dependencies:

- Overlay composables must not import the Gemini client.
- Accessibility code must not read API keys or transcript history.
- Audio code must not know about Compose or editor nodes.
- ViewModels must not instantiate Android Keystore, WebSocket, or Accessibility services directly.
- The application singleton must not own an active microphone, socket, or editor transaction.

### 16.3 Core contracts

Define interfaces before implementations. The minimum contracts are:

```kotlin
interface DictationCommandSink {
    suspend fun start(target: TargetToken): StartResult
    suspend fun stop(sessionId: SessionId): Boolean
    suspend fun cancel(sessionId: SessionId, reason: CancelReason): Boolean
}

interface TargetGateway {
    fun currentEligibility(): Flow<TargetEligibility>
    fun captureTarget(sessionId: SessionId): TargetToken?
    suspend fun insert(target: TargetToken, text: String): InsertionResult
}

interface GeminiLiveSession {
    suspend fun awaitReady()
    suspend fun sendAudio(chunk: AudioChunk): SendResult
    suspend fun endActivity()
    fun events(): Flow<GeminiEvent>
    suspend fun close()
}
```

The exact API may differ, but the properties are mandatory:

- Commands are session-scoped.
- Target capture is explicit and immutable.
- Insertion returns a typed result, not a Boolean with hidden causes.
- Gemini readiness is distinct from TCP/WebSocket connected.
- Resource closure is idempotent.

### 16.4 Accessibility service implementation

Implement the service in this sequence:

1. Declare only required accessibility capabilities and event types.
2. Configure `canRetrieveWindowContent=true` and interactive-window retrieval.
3. Track the focused editable node and a stable editor identity.
4. Read the interactive-window list to determine keyboard visibility/context.
5. Classify security using all reliable available signals; uncertain means ineligible.
6. Publish a small immutable `TargetEligibility` object.
7. Capture a `TargetToken` at bubble tap, including session ID, package, display, window/editor identity, generation, input type/security flags, and selection snapshot where observable.
8. Invalidate the generation on every relevant focus change, including resource-less fields.
9. Own the serialized insertion transaction and validate immediately before commit.
10. On service disconnect, cancel the active session and remove/recover the overlay.

Do not use reflective legacy insertion as the default API 34/35 implementation. Build an API-level adapter around the supported `InputMethod`/`AccessibilityInputConnection` surface used by the reference behavior, and test unavailable/null connection recovery explicitly.

### 16.5 Persistent overlay implementation

Use one persistent overlay host per display/service lifetime rather than adding/removing Compose views on every accessibility event.

Required host behavior:

- A real stable `LifecycleOwner`, `SavedStateRegistryOwner`, and `ViewModelStoreOwner`.
- One stable `ViewModelStore` for the host lifetime.
- Main-thread-only WindowManager operations.
- An explicit `Attached`, `Detached`, `AttachFailed`, and `Recovering` internal status.
- `addView()` success is recorded only after it returns successfully.
- Any attach/update exception is surfaced as a typed diagnostic and triggers retry or teardown.
- Bubble and expanded panel have tested bounds and touch policies.
- Display changes create a new display-specific context and recompute geometry.

The bubble should be `WRAP_CONTENT` only if its measured bounds are verified. Otherwise use explicit pixel dimensions derived from density and test them against the rendered Compose content. Do not silently mix guessed display metrics and IME metrics.

### 16.6 Foreground-service implementation

The foreground service is the active-session owner, not a notification-only proxy.

Startup protocol:

```text
Accessibility bubble tap
  -> validate target and permissions
  -> create canonical SessionId
  -> start foreground service with SessionId
  -> service calls startForeground()
  -> service emits ForegroundReady(SessionId)
  -> service opens Gemini session and awaits setup acknowledgement
  -> service starts AudioRecord
  -> service emits Listening(SessionId)
```

If any step fails, later steps must not start. The service must use `START_NOT_STICKY` unless a deliberate restart protocol is implemented. Every notification action must include the session ID and reject stale actions.

### 16.7 Audio pipeline implementation

Use a single producer/consumer pipeline:

```text
AudioRecord thread
  -> bounded channel
  -> serialized Gemini sender
  -> sent-chunk accounting
```

Rules:

- Derive chunk size from sample rate and 20 ms duration, never from a magic byte count alone.
- Accumulate partial reads until a complete frame exists.
- Treat `ERROR_DEAD_OBJECT`, initialization failure, permission loss, and read failure as typed terminal errors.
- Stop the capture producer before beginning finalization.
- Await sender drain before `endActivity()`.
- Send one and only one activity-end boundary.
- Release the recorder from one owner exactly once.
- Expose amplitude at approximately 20 Hz and never pass raw buffers to UI.

### 16.8 Gemini implementation and protocol lock

Before coding the final client, record the official documentation URL, retrieval date, model identifier, request schema, response schema, authentication constraints, supported audio format, and deprecation risk in `docs/GEMINI_LIVE_PROTOCOL.md`.

The client must have these explicit phases:

```text
Disconnected
Connecting
SetupSent
Ready
Streaming
Ending
Completed
Failed
Closed
```

Do not call the socket `Ready` merely because the TCP/WebSocket handshake succeeded. Do not start audio until the server setup acknowledgement is received. Protocol fixtures must assert exact JSON ordering where order is semantically required and exact message cardinality for activity end.

### 16.9 Insertion transaction and verification

The transaction must be serialized by the accessibility service:

1. Confirm the session is still current.
2. Reacquire the active editor and input connection.
3. Compare the target snapshot with the current editor.
4. Reject secure/uncertain targets.
5. Commit the selected text once.
6. Verify the expected surrounding-text/selection advance where observable.
7. Mark the result consumed only after the commit transaction completes.
8. Report success or a typed failure.

If commit status is ambiguous, do not blindly retry because that can duplicate text. Offer Copy fallback and explain that direct insertion could not be confirmed.

### 16.10 Resource and failure ownership

Every session owns and closes:

- AudioRecord.
- Audio channel/queue.
- Gemini session/socket.
- Foreground notification/service state.
- Session-scoped coroutine jobs.
- Candidate memory.

Cleanup must be idempotent and execute from `finally` on all normal, error, cancellation, timeout, process, and service-disconnect paths. Tests must verify each resource closes exactly once.

---

## 17. Detailed UI/UX Plan

### 17.1 Visual direction

The reference interaction is compact, calm, and utility-first: a small high-contrast microphone affordance that becomes a focused recording surface only after explicit interaction. WhisperType should preserve that hierarchy while using its own visual identity.

Recommended visual language:

- Deep neutral surface with a single warm accent for recording.
- One primary typeface family, using Android system font fallback.
- Strong contrast between idle, listening, finalizing, success, and error states.
- Minimal text in the bubble.
- No decorative animation that competes with the editor.
- Soft rounded surfaces, restrained elevation, and predictable placement.

Do not reproduce logos, proprietary icons, exact brand colors, or copied screenshots. Match the interaction rhythm and information hierarchy, not the protected brand identity.

### 17.2 UI state specifications

| State | Visible surface | Primary action | Secondary action | Required feedback |
| --- | --- | --- | --- | --- |
| Hidden | None | None | None | No overlay ineligible contexts |
| Idle bubble | Small mic bubble | Start | None | Accessible label and pressed state |
| Starting | Recording panel | Cancel | None | Starting indicator; controls disabled until ready |
| Listening | Recording panel | Stop | Cancel | Waveform, listening label, optional timer |
| Finalizing | Recording panel | None | Cancel only if safe | Settling waveform and finalizing label |
| Inserting | Short transition surface | None | None | Inserting status; prevent duplicate actions |
| Success | Brief confirmation | None | None | Return to idle and preserve keyboard |
| Copy available | Result/fallback surface | Copy | Dismiss | Explain direct insertion was unavailable |
| Error | Recovery surface | Retry only where safe | Dismiss | Actionable typed error, never a dead Close button |

### 17.3 Layout requirements

- Bubble touch target: minimum 48 dp; preferred 52-60 dp depending on device density.
- Bubble must not be clipped by display cutouts, gesture areas, or rounded corners.
- Recording panel controls must remain reachable at 1.3x font scale.
- Stop and Cancel must differ by icon, label, placement, and semantics.
- Use test tags for every action in debug builds.
- Avoid coordinate-based UI tests; use Compose semantics and visible text/content descriptions.
- Use a static waveform fallback when reduced motion is enabled.
- Ensure the overlay is usable with TalkBack and does not create an inaccessible focus trap.

### 17.4 Motion plan

- Bubble appearance/disappearance: short fade/scale transition, approximately 150-220 ms.
- Panel expansion: approximately 180-240 ms when animation is permitted.
- Listening waveform: responsive amplitude with bounded smoothing.
- Finalizing waveform: settle to a static/low-amplitude state.
- Error and Copy surfaces: no looping animation.
- Reduced-motion mode: remove scale/pulse and use opacity or immediate state changes.

Every animation must have a testable end state and must not delay microphone start, stop, cancellation, or insertion.

### 17.5 UX copy

Use concise, actionable strings:

- `Start dictation`
- `Listening`
- `Stop`
- `Cancel`
- `Finalizing`
- `Inserting`
- `Copy text`
- `Copied`
- `Could not insert text`
- `Reconnect microphone permission`
- `Reconnect Accessibility`

Never show API keys, transcript content in notifications, internal exception messages, or ambiguous claims such as `Copied` before the clipboard action occurs.

### 17.6 Accessibility and interaction QA

For every screen and overlay state, verify:

- Text and controls are visible in light and dark themes.
- Contrast is sufficient.
- Controls have semantic roles and labels.
- TalkBack announces state changes without reading private editor content.
- Font scaling does not clip Stop, Cancel, Copy, or Dismiss.
- Touch targets meet Android minimums.
- Back/cancel behavior is deterministic.
- System gestures do not accidentally trigger dictation.
- Rapid taps are debounced at the command layer, not only visually.

---

## 18. Asset Repurposing and Reference Fidelity

### 18.1 Asset policy

The supplied Whisper Flow APKM is a behavioral and technical reference. Its contents may be inspected to understand observable layout, state transitions, permissions, window types, and insertion recovery. WhisperType must not ship copied Wispr Flow source, proprietary assets, trademarks, package names, or extracted artwork unless the user has documented rights to use them.

The asset workflow is:

1. Inventory reference resources by type, dimensions, density, and apparent purpose.
2. Classify each item as behavior evidence, generic Android resource, third-party library resource, brand asset, or unknown-license asset.
3. Keep reference assets outside the application source tree.
4. Recreate required icons and illustrations as original vector or Compose assets.
5. Use Android system icons only when they are generic and appropriate.
6. Record provenance and license for every shipped non-system asset.
7. Run APK/resource scans to ensure no reference package names or original asset identifiers remain.

### 18.2 What may be repurposed

Safe candidates, subject to normal licensing review:

- Generic Android dimensions and accessibility patterns.
- Generic interaction concepts such as a microphone button, waveform, stop/cancel affordances, and permission status rows.
- Publicly documented Android API usage.
- Original WhisperType-created adaptations of observed layouts and state hierarchy.
- Test fixtures that contain no proprietary code, artwork, or private user data.

### 18.3 What must be recreated

Recreate rather than extract:

- Microphone icon.
- Bubble shape, color palette, and branding.
- Recording waveform visuals.
- Empty, error, success, and Copy illustrations.
- App icon and splash assets.
- Onboarding screenshots or diagrams.
- Strings and labels.
- Motion specifications and transitions.

The result should feel familiar in use but unmistakably belong to WhisperType.

### 18.4 Asset implementation checklist

For each new asset, record:

```text
Asset name:
Purpose:
Source:
License/provenance:
Dark-mode variant:
Light-mode variant:
Minimum size:
Content description:
Screenshot-test coverage:
```

Prefer vectors for icons, density-independent Compose drawing for waveform and simple surfaces, and raster images only when there is a real quality requirement. Do not put private reference screenshots or extracted APK resources into the release APK.

---

## 19. Whisper Flow Replication Workflow

“Perfectly replicate” means matching the validated observable workflow as closely as Android platform and clean-room constraints allow. It does not mean copying proprietary implementation or assuming decompiled code is correct.

### 19.1 Reference study workflow

For each reference behavior:

1. Observe the behavior manually on the reference app where possible.
2. Record a short screen capture or screenshot outside Git, with no private user content.
3. Inspect the APKM manifest, resources, and decompiled control flow to form hypotheses.
4. Mark the hypothesis as confirmed only after a reproducible observation or platform-level test.
5. Convert the behavior into a WhisperType acceptance test.
6. Implement the behavior independently with original code/assets.
7. Compare the result against the behavior matrix using the same device conditions.
8. Document intentional differences caused by platform, privacy, licensing, or product decisions.

### 19.2 Behavior mapping table

Maintain a table with these columns:

| Reference behavior | Evidence | WhisperType requirement | Implementation owner | Test | Difference/rationale |
| --- | --- | --- | --- | --- | --- |
| Persistent small bubble | APK analysis + observation | Persistent touchable overlay | Accessibility/overlay | Physical smoke test | Original assets/branding |
| Bubble eligibility | Observation + service analysis | Focus/keyboard/security gate | Accessibility | Eligibility matrix | Fail closed on uncertainty |
| Recording expansion | Observation | Panel with waveform and controls | UI/overlay | Compose + physical | No copied artwork |
| Text insertion | Decompiled API path + tests | Validated input-method transaction | Accessibility | Editor host + device | Current official API surface |
| Insertion recovery | Decompiled retry/verification flow | Typed retries before ambiguous commit | Accessibility | Connection failure suite | Simplified where safe |
| Service recovery | Decompiled watchdog behavior | Visible recovery and reconnect path | Service/onboarding | Kill/re-enable tests | Original diagnostics |

### 19.3 Reference-matching acceptance criteria

The implementation is considered reference-matched when:

- Bubble visibility timing is equivalent within an agreed tolerance.
- Bubble position, size, and opacity are within documented device-adjusted tolerances.
- Tap behavior and state transition sequence match.
- Recording panel presents the same essential information hierarchy.
- Stop/cancel semantics match.
- Keyboard selection and editor focus are preserved.
- Insertion occurs once in the original field or produces explicit fallback.
- Recoverable failures result in visible recovery rather than a silent dead overlay.
- Accessibility and notification disclosures accurately describe WhisperType's behavior.

Exact pixel equality is not a valid target across different phones, densities, fonts, OEMs, and system bars. Use reference screenshots for design review, but use semantic, geometric, and interaction tolerances for acceptance.

### 19.4 Replication anti-patterns

Do not:

- Copy decompiled classes into WhisperType.
- Rename packages or classes to mimic the reference.
- Extract reference artwork into production without license approval.
- Copy hidden permissions without proving they are needed.
- Treat static APK analysis as proof of runtime behavior.
- Preserve a behavior that conflicts with Android security or current API requirements merely because the reference does it.
- Claim parity before the Samsung SwiftKey physical test passes.

---

## 20. Implementation Definition of Done

A workstream is complete only when:

- Its allowed files are the only production files changed.
- Its public contracts are documented.
- Its unit/UI/device tests are present and meaningful.
- Tests do not merely assert an internal readiness flag where visible behavior is required.
- Failures are typed and actionable.
- Cleanup is tested on success, failure, cancellation, timeout, process death, and service disconnect.
- Privacy scans pass.
- The required report format is complete.
- Integration risks and device dependencies are explicit.

The product is not complete when it merely builds. It is complete when the physical end-to-end workflow is observable, repeatable, safe, and documented.
