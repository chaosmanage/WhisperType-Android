# WhisperType Android — Accessibility Design

This document explains why WhisperType requires Accessibility access, the exact capabilities the service requests, how windows and the keyboard are detected, how the overlay is layered, how secure fields are excluded, the InputConnection restrictions, OEM differences, and the Phase 0 technical spike results.

## Why Accessibility access is required

WhisperType does not replace the user's keyboard and has no direct API for "insert text into another app's focused field while that app is backgrounded." The Android accessibility API is the only supported mechanism that provides, without root:

- notification of focus changes (`TYPE_VIEW_FOCUSED`, `TYPE_WINDOW_STATE_CHANGED`),
- the bounds of the on-screen keyboard (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`),
- the interactive window list (`getWindows()`),
- a path to the focused editor's input connection for final text insertion.

The service is scoped to four uses only: editable-field detection, keyboard-window bounds, input connection access, and final text insertion. It never inspects, stores, or logs unrelated screen content.

## Exact service capabilities

Declared in `app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewFocused"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds|flagInputMethodEditor"
    android:canRetrieveWindowContent="true"
    android:canRequestFilterKeyEvents="false"
    android:notificationTimeout="50"
    android:settingsActivity="com.whispertype.android.MainActivity" />
```

- `canRequestFilterKeyEvents="false"` — WhisperType never intercepts or filters key events.
- `canRetrieveWindowContent="true"` — required to resolve the focused editable node and read window bounds; content is only read to the extent of detecting the editable field and its class name for input-type inference.
- `flagInputMethodEditor` — grants access to the IME window; `flagReportViewIds` provides view ids used as a stable `fieldId`.
- The disclosure shown to users (from `res/values/strings.xml`): "WhisperType detects when a text field is focused and the keyboard is visible so it can place a small microphone control at the keyboard boundary. It inserts the dictated text at the cursor when you tap Stop. It does not read or store unrelated screen content."

Behavior in `WhisperTypeAccessibilityService`: on `onServiceConnected` it creates the two trackers and the overlay controller, registers an `ACTION_SCREEN_OFF` receiver, and starts collecting bridge state, settings, IME bounds, and active input. It cancels the active dictation on focus change (different package/window), IME loss (after a 500 ms grace), screen off, and mic-permission revocation. `onDestroy` removes all overlay windows and clears the `shared` reference.

## Window and IME detection

- `InputMethodWindowTracker` reads only `TYPE_INPUT_METHOD` windows from `getWindows()`. Bounds are validated: non-empty, bottom-attached within 8 px of the display edge, width >= 66% of the display, height between 150 px and 60% of the display (rejects floating and split keyboards).
- Geometry is debounced: a candidate is emitted when observed twice in a row, or once 300 ms have elapsed without a change; movement below an 8 px threshold is ignored. An invalid or missing IME window emits `null` immediately, which hides the dock.
- `InputTargetTracker` resolves the focused editable node from `TYPE_VIEW_FOCUSED` events or `findFocus(FOCUS_INPUT)`, skips flag-secure windows, and infers `inputType` from the node class name. The accessibility API does not expose the real `EditorInfo`, so the input type defaults to `TYPE_CLASS_TEXT` and is promoted to `TYPE_TEXT_VARIATION_PASSWORD` when the class name contains `password` or `pin`. Selection offsets are not exposed and default to 0.

## Overlay window type and flags

Both dock and voice panel are `TYPE_ACCESSIBILITY_OVERLAY` windows (`OverlayWindowController.windowParams`):

```text
WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN
PixelFormat.TRANSLUCENT
```

This layering appears above the IME, receives touches inside its own bounds, passes touches outside through to the app, never invokes keyboard switching, never moves the cursor, never dismisses the keyboard, and never takes input focus. Dock placement (position, size, overlap mode, opacity, theme, accent) is computed by the pure `OverlayGeometryCalculator` against the validated IME bounds and display bounds; the voice panel covers the IME bounds exactly.

## Secure-field exclusion

Two independent layers prevent WhisperType from touching sensitive input:

1. `InputTargetTracker` never tracks nodes hosted in flag-secure windows (checked reflectively via `getFlagsEx`/`getFlags`, bit 3). No token is ever created for such fields.
2. `SecureFieldClassifier.isSecure(inputType, isFlagSecure)` additionally rejects password/PIN input-type variations: text password, visible password, web password, number password, and numeric PIN (literal `0x00000020`, since the platform removed `TYPE_NUMBER_VARIATION_PIN` from the SDK on API 34). Bare variation constants match any input class; class-mismatched variations do not collide.
3. At insertion time the controller re-checks `SECURE_FIELD` against the live service state.

Because secure windows are never tracked, the classifier is always evaluated with `isFlagSecure = false` in production.

## InputConnection restrictions

- The accessibility API does not expose the true `EditorInfo`; input type and selection offsets are approximations (documented in `InputTargetTracker`).
- A reliable input connection exists only while the target app is foregrounded and the field is focused. Some fields and surfaces never expose one: password/PIN/payment fields, private-browsing fields, unusual WebViews, canvas-based editors, and remote-desktop applications. In those cases insertion fails with a recoverable code and the result is offered as an explicit Copy fallback; WhisperType never silently pastes into an uncertain field.
- Android 16 (API 36) removed `AccessibilityNodeInfo.createInputConnection`. `AccessibilityInsertionController` uses `InputMethod.getCurrentInputConnection()` (guarded by `currentInputStarted`) on API 36+ and the legacy reflective call on API 34/35.
- Insertion re-validates the live state against the session's `TargetToken` (service identity, lock state, package, display, input generation, secure-field status, consumed session id) before committing — see `docs/ARCHITECTURE.md`.

## OEM differences

- Accessibility settings labels and flows differ: Pixel/stock Android uses `Settings -> Accessibility -> Downloaded/Installed apps`, Samsung uses `Settings -> Accessibility -> Installed apps`. The in-app onboarding launches the generic accessibility settings screen (`Settings.ACTION_ACCESSIBILITY_SETTINGS`).
- Samsung devices may stop the Accessibility Service under aggressive battery management; the service reconnects when re-enabled from `Settings -> Accessibility -> Installed apps -> WhisperType`.
- Keyboard geometry varies by IME and OEM build (candidate bar heights, one-handed modes); the 300 ms debounce and shape validation are designed to suppress flicker. The `notificationTimeout=50` keeps event latency low.
- Android 16 changes the input connection API as noted above; the committer falls back to `commitText` semantics via reflection on older API levels.

## Technical spike results

Implementation Plan Phase 0 (section 5) gated the design on these assumptions:

- A minimal app with the accessibility service, mic permission, foreground microphone service, and a Compose overlay is sufficient to validate the whole flow; no broader screen-reading behavior is requested.
- Keyboard detection from `TYPE_INPUT_METHOD` windows with bottom-attachment and size validation — see the Wispr parity rebuild; the assumption is not yet device-validated.
- The production overlay is now `TYPE_APPLICATION_OVERLAY` (with `SYSTEM_ALERT_WINDOW`), matching Wispr Flow, **not** `TYPE_ACCESSIBILITY_OVERLAY` (locked decision §3 / Phase 1). The previous "validated" claim for `TYPE_ACCESSIBILITY_OVERLAY` was incorrect and mirrored the `2038` misidentification; it has been removed.
- A foreground `microphone` service started from the bubble tap while the app is backgrounded shows the recording notification and the system mic privacy indicator, and stops cleanly from the overlay.

### Physical-device status

The following were claimed as verified in earlier revisions and are **NOT RUN /
withdrawn** pending a physical Samsung gate (WisprFlow-Parity-Rebuild-Plan §2.8):
overlay layering above an active IME, touch pass-through without taking focus,
keyboard open/close cycles, rotation, one-handed mode, gesture and three-button
navigation, screen lock/unlock, and app switching. A compilation / unit-test /
lint / APK-install result is never overlay or insertion evidence.
