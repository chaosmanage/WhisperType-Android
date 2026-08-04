# WhisperType Wispr Flow Parity Rebuild Plan

**Status:** Execution plan after Phase 2 failure  
**Reference build:** Wispr Flow `2.1.5-142` supplied as APKM  
**First device gate:** Samsung Galaxy S25, Android 15, SwiftKey  
**Scope:** Rebuild the overlay, accessibility, insertion, and interaction runtime from scratch. Replace only the voice/transcription backend with Gemini Live.

## 1. Diagnosis

The prior PRD encoded the wrong overlay architecture.

Wispr Flow's APK directly proves that it uses:

- `SYSTEM_ALERT_WINDOW`.
- `WindowManager.LayoutParams` type `2038`, which is `TYPE_APPLICATION_OVERLAY`.
- A normal main-process foreground service, `FlowService`, as the overlay owner.
- A separate accessibility-process service for editor detection and insertion.
- `Settings.canDrawOverlays()` as the overlay permission gate.

Evidence:

- `/tmp/opencode/wispr/apktool_out/AndroidManifest.xml:2`
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/flowapp/service/FlowService.java:1134`
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/flowapp/service/FlowService.java:3197`

The previous reverse-engineering document incorrectly claimed that numeric type `2038` was `TYPE_ACCESSIBILITY_OVERLAY`. It is not:

- `2032` = `TYPE_ACCESSIBILITY_OVERLAY`
- `2038` = `TYPE_APPLICATION_OVERLAY`

That error propagated into:

- `PRD/WhisperType-Android-PRD.md`
- `docs/WISPR_REVERSE.md`
- `docs/REBUILD_DECISION_RECORD.md`
- `docs/BEHAVIOR_MATRIX.md`
- The implementation's overlay window type

This was the primary PRD failure: the builder was instructed to imitate Wispr while being required to use a fundamentally different windowing mechanism.

## 2. Additional Implementation Failures

### 2.1 Wrong context passed to the overlay host

The accessibility service passes `baseContext` to `PersistentOverlayHost` instead of the accessibility service instance:

- `app/src/main/java/com/whispertype/android/platform/accessibility/WhisperTypeAccessibilityService.kt:65-69`

The host then obtains `WindowManager` from that base context:

- `app/src/main/java/com/whispertype/android/platform/overlay/PersistentOverlayHost.kt:94-109`

Therefore the report's statement that Attempt C tested the service context is inaccurate. It tested `service.baseContext`, not `service` itself. This is a plausible contributor to the `BadTokenException` and must be tested before attributing the failure to Samsung or Android 15.

### 2.2 Missing Compose owners

The current implementation creates a bare `ComposeView`:

- `app/src/main/java/com/whispertype/android/platform/overlay/PersistentOverlayHost.kt:99`

It does not install stable:

- `LifecycleOwner`
- `SavedStateRegistryOwner`
- `ViewModelStoreOwner`

Wispr installs all three before attaching its Compose container:

- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/flowapp/service/FlowService.java:3222-3226`

The old project already recorded a `ViewTreeLifecycleOwner` crash in `docs/FAILURES.md`. The same class of failure must not be allowed to recur.

### 2.3 Eligibility can hide an attached window

The overlay can attach successfully while rendering no visible bubble because the Compose content is gated by `TargetEligibility`.

The current eligibility requires all of these conditions simultaneously:

- Service connected.
- Editable field focused.
- Field not secure.
- Field not uncertain.
- Keyboard visible.
- Microphone granted.
- API key configured.
- App enabled.
- No active session.

There is no on-device diagnostic that identifies which condition hid the content. Window attachment and bubble visibility are therefore conflated.

Additional eligibility defects:

- Focus is not initialized on service connection.
- `TYPE_WINDOWS_CHANGED` refreshes keyboard visibility but not focused-editor state.
- `inputType == 0` is treated as uncertain.
- The implementation accepts only `node.isEditable`, which is too narrow for some custom editors.
- Ordinary email, URI, phone, and other text variations are rejected by the current classifier.

Relevant files:

- `app/src/main/java/com/whispertype/android/platform/accessibility/WhisperTypeAccessibilityService.kt:60-85`
- `app/src/main/java/com/whispertype/android/platform/accessibility/EditorTracker.kt:162-207`
- `app/src/main/java/com/whispertype/android/core/model/TargetEligibility.kt:18-21`

### 2.4 Placement diverges from Wispr

Wispr creates a persistent `WRAP_CONTENT` window with a 56 dp bubble and places it at the right edge around vertical center:

- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/flowapp/service/FlowService.java:1134-1148`
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/design/DimenKt.java:63-74`

WhisperType currently uses an explicit square window anchored to the top edge:

- `app/src/main/java/com/whispertype/android/platform/overlay/PersistentOverlayHost.kt:147-171`

That is not the Wispr interaction or geometry model.

### 2.5 Insertion is not cursor-aware

The current implementation calls `ACTION_SET_TEXT` with the dictated text:

- `app/src/main/java/com/whispertype/android/platform/accessibility/AccessibilityTargetGateway.kt:72-89`

`ACTION_SET_TEXT` replaces the node's complete text. It is not equivalent to `InputConnection.commitText()` and cannot correctly preserve surrounding text or replace only the selected range.

Wispr uses:

- `InputMethod.AccessibilityInputConnection.getCurrentInputConnection()`
- `getSurroundingText()`
- `commitText()`
- Selection-advance verification

Evidence:

- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/ui/accessibility/InputAccessibilityService.java:1604-1647`
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/ui/accessibility/InputAccessibilityService.java:1672-1678`
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/ui/accessibility/InputAccessibilityService.java:2528-2533`

### 2.6 Required accessibility IME integration is absent

Wispr's accessibility configuration includes:

- `flagInputMethodEditor`
- `flagReportViewIds`
- `flagRequestFilterKeyEvents`
- `flagRetrieveInteractiveWindows`
- `canRetrieveWindowContent=true`

WhisperType's current configuration omits `flagInputMethodEditor`:

- `app/src/main/res/xml/accessibility_service_config.xml:3-8`

WhisperType also does not override `onCreateInputMethod()`. Without that surface, it cannot reproduce Wispr's insertion path.

### 2.7 Recovery is not implemented

The current overlay records an attach failure but does not schedule a bounded retry or recreate the display-specific host:

- `app/src/main/java/com/whispertype/android/platform/overlay/PersistentOverlayHost.kt:113-117`
- `app/src/main/java/com/whispertype/android/platform/accessibility/WhisperTypeAccessibilityService.kt:70-71`

A transient timing failure therefore becomes permanent until the service is restarted.

### 2.8 Device-dependent claims were falsely marked as verified

`docs/ACCESSIBILITY_DESIGN.md` claims that physical overlay layering, keyboard cycles, rotation, and foreground microphone behavior were validated. `FailureReport.md` proves that the bubble never attached and insertion was never reached.

The rebuild must remove or correct these claims. Every physical result must carry one of:

- `PASS`
- `FAIL`
- `BLOCKED`
- `NOT RUN`
- `INCONCLUSIVE`

Compilation, unit tests, lint, APK installation, and internal state flags are never overlay or insertion evidence.

## 3. Locked Rebuild Decisions

- Rebuild the runtime without preserving the current overlay/accessibility architecture.
- Do not implement migrations or backward compatibility.
- Use `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW`, matching Wispr Flow.
- Make a normal foreground `FlowRuntimeService` own the overlay lifecycle, visible bubble, microphone session, session state, and Gemini Live connection.
- Run `WhisperTypeAccessibilityService` in a separate `:accessibility` process.
- Use typed IPC between the runtime service and accessibility service.
- Use accessibility IPC for focus state, target capture, insertion, and recovery only.
- Subscribe initially to `TYPE_WINDOWS_CHANGED`, matching Wispr's observed event pipeline.
- Enable `flagInputMethodEditor`, `flagReportViewIds`, and `flagRetrieveInteractiveWindows`.
- Implement `onCreateInputMethod()` and cursor-aware `commitText()`.
- Attach one persistent `WRAP_CONTENT` overlay and show/hide its Compose content through state instead of repeatedly adding and removing windows.
- Recreate all shipped visual assets and branding. Reference artwork is evidence only unless rights are documented.
- Keep Gemini Live out of the first physical overlay and insertion gates.

## 4. Reference Mechanics To Reproduce

### 4.1 Overlay owner

Wispr's `FlowService` is a foreground service implementing:

- `LifecycleOwner`
- `SavedStateRegistryOwner`
- `ViewModelStoreOwner`

It obtains `WindowManager` from its own service context and creates the overlay window in the main process.

### 4.2 Overlay parameters

The observed reference parameters are:

```text
width  = WRAP_CONTENT
height = WRAP_CONTENT
alpha  = approximately 0.8 by default
```

The reference uses a 56 dp bubble height, 20 dp edge margin, 16 dp shadow padding, and a 0.85 default size multiplier. These values are starting measurements, not a substitute for same-device visual comparison.

### 4.3 Persistent attachment

Wispr creates the overlay once after permission and onboarding gates pass. Its content is revealed or hidden by UI state. The implementation must not add and remove the window for every accessibility event.

### 4.4 Accessibility event model

Wispr declares only `typeWindowsChanged` as its primary event stream and derives focus, keyboard visibility, and interactive-window state from the current window list. WhisperType should start from the same model and add events only when a device test proves they are necessary.

### 4.5 Insertion model

The insertion transaction must:

1. Capture the active package, display, window, editor identity, session ID, generation, selection, and surrounding-text fingerprint.
2. Reacquire the current input method and `AccessibilityInputConnection`.
3. Recover a null or not-yet-started input session where possible.
4. Read surrounding text before committing.
5. Call `commitText()` once at the current selection.
6. Verify the selection advance or surrounding-text change.
7. Treat ambiguous results as ambiguous. Never blindly retry.
8. Offer explicit Copy fallback when direct insertion cannot be confirmed.

## 5. Execution Phases

### Phase 0: Reference Evidence

Record Wispr Flow on the target Samsung under controlled conditions:

- Idle bubble appearance, dimensions, opacity, edge margin, and vertical position.
- Bubble appearance and disappearance timing when fields and keyboards open or close.
- Tap, drag, snooze, recording, processing, success, copy, and error states.
- Portrait and landscape.
- SwiftKey and at least one other keyboard.
- Normal, password, PIN, and secure fields.
- App switching, lock/unlock, and keyboard hide/show.

Store screenshots or video outside Git with no private text.

**Exit gate:** Every required behavior has observed evidence, extracted constants, or is explicitly marked unknown.

### Phase 1: Minimal Application Overlay

Build only:

- Overlay-permission onboarding.
- `FlowRuntimeService`.
- One plain Android `View`, not Compose.
- `TYPE_APPLICATION_OVERLAY`.
- `WRAP_CONTENT` dimensions.
- Translucent, non-focusable, touchable window.
- Wispr-style right-edge, vertically centered initial placement.
- Stable attach/remove diagnostics.

Do not implement accessibility eligibility, insertion, audio, or Gemini in this phase.

**Samsung gate:** A colored 56 dp surface appears on the Samsung, receives taps, does not steal editor focus, and survives 20 show/hide cycles.

Required evidence:

- `Settings.canDrawOverlays()` result.
- Package/service process IDs.
- `dumpsys window` showing the `2038` window.
- Logcat showing successful `addView()`.
- User-observed tap and focus behavior.

### Phase 2: Persistent Compose Bubble

Replace the plain view with a lifecycle-backed container:

- Stable lifecycle owner.
- Stable saved-state owner.
- Stable view-model-store owner.
- One persistent window attachment.
- Bubble content hidden/revealed through state.
- Layout-change correction when expanded content changes width.
- Recomputed position after rotation and display changes.
- Bounded retry after recoverable attach failures.

Implement only original WhisperType visuals, with measurements based on Phase 0 reference evidence.

**Samsung gate:** The bubble is visible, touchable, correctly positioned, and stable across rotation and 50 visibility cycles.

### Phase 3: Accessibility Process

Implement the separate accessibility service:

- `android:process=":accessibility"`.
- `typeWindowsChanged`.
- `flagInputMethodEditor`.
- `flagReportViewIds`.
- `flagRetrieveInteractiveWindows`.
- `onCreateInputMethod()`.
- Confidence states equivalent to `EditableFocusedWithKeyboard`, `EditableFocused`, and `SoftKeyboardVisible`.
- Password and secure-field exclusion.
- Typed IPC messages to the runtime service.
- Per-condition diagnostics explaining every hidden-bubble decision.

**Samsung gate:** Focusing a normal SwiftKey editor reveals the existing application overlay. Removing focus hides it. Password and PIN fields never reveal it.

### Phase 4: Static Cursor-Aware Insertion

Implement insertion before any audio work:

- Use `AccessibilityInputConnection`.
- Capture and validate target identity.
- Read `getSurroundingText()`.
- Use `commitText()` at the cursor or selected range.
- Verify cursor/selection advancement.
- Recover null or unstarted input sessions.
- Never retry an ambiguous commit.
- Route unavailable or ambiguous insertion to functioning Copy fallback.

Test fields:

- Empty single-line field.
- Existing text with cursor in the middle.
- Selected range.
- Multiline field.
- Email field.
- Password/PIN field.
- Two fields in the same package and window.
- Focus switch during insertion.

**Samsung gate:** Static text inserts exactly once at the cursor, replaces selected text, preserves surrounding text, preserves SwiftKey, and rejects focus changes.

### Phase 5: Visual State Parity

Implement the observed bubble state hierarchy:

- Idle.
- Starting.
- Listening with waveform.
- Finalizing/processing.
- Success.
- Copy available.
- Recoverable error.
- Accessibility and permission repair.

Use recreated assets. Do not copy Wispr artwork, logos, strings, package names, or proprietary code.

**Exit gate:** Same-device side-by-side comparison meets documented tolerances for size, position, color, opacity, shape, state hierarchy, and transition timing.

### Phase 6: Gemini Live Substitution

Only after overlay and insertion gates pass:

- Keep `FlowRuntimeService` as the active-session owner.
- Start microphone capture only after explicit bubble interaction.
- Promote to microphone foreground mode before `AudioRecord`.
- Await Gemini setup acknowledgement before audio.
- Stream exact PCM16 frames.
- Drain accepted audio before one activity-end boundary.
- Validate raw and cleaned candidates.
- Pass the selected candidate to the already proven insertion transaction.

**Samsung gate:** One English and one Latin-script Hinglish utterance insert correctly into SwiftKey fields.

### Phase 7: Hardening

Run the following matrix:

- 50 keyboard show/hide cycles.
- 20 consecutive dictation sessions.
- Selection replacement.
- Two fields in the same app.
- Focus switch during recording and finalization.
- Password/PIN fields.
- Rotation during idle, recording, and finalization.
- App switch.
- Lock/unlock.
- Process death and service restart.
- Microphone permission revocation.
- Accessibility service disconnect.
- Network failure.
- Microphone contention.
- Overlay permission revocation.

**Release gate:** Zero crashes, duplicate insertions, wrong-field insertions, stuck overlays, leaked microphone sessions, or silent failures.

## 6. Builder Protocol

Every phase must produce:

- Exact changed files.
- Exact build identifier and APK hash.
- Tests run and complete output summary.
- Device model, Android version, keyboard, and keyboard version.
- ADB evidence for service and window state.
- User-observed visual result.
- Screenshot or video reference when visual behavior is claimed.
- Explicit `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN` status.
- Known limitations and the next gate.

The builder must stop immediately when a physical gate fails. Compilation, unit tests, lint, APK installation, an internal “attached” flag, or inferred APK behavior are never substitutes for an observed window and successful device interaction.

## 7. Forbidden Shortcuts

- Do not keep `TYPE_ACCESSIBILITY_OVERLAY` as the production overlay after switching to the Wispr architecture.
- Do not claim that type `2038` is accessibility overlay.
- Do not use `ACTION_SET_TEXT` as the primary insertion path.
- Do not mark a bubble visible merely because a window was added.
- Do not render a bare Compose view without lifecycle owners.
- Do not require an API key before proving the static overlay and insertion loop.
- Do not add Gemini/audio work while the physical overlay or static insertion gate is failing.
- Do not use unit tests as evidence of OEM window behavior.
- Do not use screenshots with private editor content in the repository.
- Do not copy Wispr proprietary source, artwork, branding, package names, analytics, or secrets.

## 8. Current Environment Blocker

At the time of diagnosis, `adb devices -l` returned no connected devices. The next physical phase must pause until the Samsung phone is connected, unlocked, authorized, and selected explicitly by serial.

Required preflight:

```bash
adb devices -l
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell settings get secure default_input_method
```

No production claim should be made until the corresponding physical gate has been run on that device.
