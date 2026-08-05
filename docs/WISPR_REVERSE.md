# Wispr Flow 2.1.5 reverse-engineering notes

Decompiled from the user-uploaded APKM bundle (`com.wispr.flowapp_2.1.5-142`,
base.apk SHA-256 `066db59e511a086a7107acc8110eedaec2d9bb9df7ee150605ae4a17fadbe4bf`)
with apktool 2.7.0 and jadx 1.5.1. Source trees:

- `/tmp/opencode/wispr/apktool_out/` — resources + merged manifest
- `/tmp/opencode/wispr/jadx_out/sources/com/wispr/` — Java sources

## 1. Manifest summary

- `minSdk=33` (Android 13+).
- Permissions: `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `RECORD_AUDIO`,
  `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`.
- Services: `FlowService` (foreground mic; owns the bubble overlay),
  `FlowAccessibilityService` + `AccessibilityBridgeService` (both
  `android:process=":accessibility"`), `MainServiceWatchdog`,
  `BootCompletedReceiver`, `ScreenUnlockReceiver`.
- Activities: `MainActivity` (singleTask), `SpeechPermissionActivity`
  (translucent), `AccessibilitySettingsPipActivity` (PIP), `PromptActivity`.
- A11y config (`res/xml/accessibility_service_config.xml`) is minimal:
  `accessibilityEventTypes="typeWindowsChanged"` ONLY;
  `flags=flagInputMethodEditor|flagReportViewIds|flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows`;
  `canRetrieveWindowContent=true`; `canRequestFilterKeyEvents=true`;
  `canPerformGestures=true`; `isAccessibilityTool=true`; `notificationTimeout=10`.

Key takeaway: Wispr subscribes to a **single** event type (`typeWindowsChanged`)
and derives everything (IME bounds, focus windows, editable targets) from the
interactive-window list on each change.

## 2. Overlay window (`FlowService.createOverlayParams`)

```
new WindowManager.LayoutParams(-2, -2, 2038, 0x1000088, -3)
```

- Type `2038` = `TYPE_APPLICATION_OVERLAY` (not `TYPE_ACCESSIBILITY_OVERLAY`;
  `2032` = `TYPE_ACCESSIBILITY_OVERLAY`). gated by `SYSTEM_ALERT_WINDOW`. — the
  production overlay uses `TYPE_APPLICATION_OVERLAY` per the rebuild.
- `WRAP_CONTENT` × `WRAP_CONTENT` — the bubble window is a small circle, never a
  full-rect blocker.
- Flags `0x1000088` = `LAYOUT_IN_SCREEN | ... | NOT_FOCUSABLE`, and notably **no
  `NOT_TOUCHABLE`** — the bubble window is touchable.
- Format `TRANSLUCENT`.
- Gravity `8388659` (start|right|center_vertical + include-system-bars).
- `x` = screen right edge − bubble width − `flowBubbleMarginPx`; `y` = screen
  height / 2. `alpha` = bubble opacity (default 0.8), scaled by bubble size
  (default 0.85).
- `BubbleOverlayContainer` = `FrameLayout` wrapping a WRAP_CONTENT `ComposeView`.

The overlay window is added **once, lazily** (`showOverlay()`: gated by
`BubbleEligibilityGate.getAllowed()` = onboarding complete, `hasOverlayPermission()`,
and `getParent() == null`). Show/hide afterwards is toggled inside Compose —
never add/remove per event.

## 3. Bubble visibility gating

`ViewFocusConfidence` enum: `EditableFocusedWithKeyboard` > `EditableFocused` >
`SoftKeyboardVisible`. `BubbleVisibilityPolicy` + `AccessibilityEventProcessor` +
`SoftKeyboardStateTracker` decide whether the bubble is visible. Gating is
focus-and-keyboard driven (confidence model), plus onboarding gate
(`BubbleEligibilityGate`) and snooze state. There is no requirement that the IME
window bounds be fully validated before the bubble may appear — the bubble is a
persistent window whose Compose content fades in/out.

## 4. Insertion (API 33+ InputMethod surface)

`InputAccessibilityService.onCreateInputMethod()` returns `WisprInputMethod`
(subclass of `InputMethod`). Insertion uses `getCurrentInputConnection()` +
`getSurroundingText()` + `commitText`. Supporting classes: `ImeSessionTextInserter`,
`SelectionSignalTracker` (`awaitConsistentAdvance`), `SurroundingTextProbe`,
`RootNodeResolver`, `WebEditorPolicy`, plus retry/verify paths
(`insertTextAndVerify`, `launchInsertionVerification`, `quickVerifyCommitSuccess`,
`cancelPendingVerification`) and focus-recovery helpers (`attemptFocusRecovery`,
`attemptInputNotStartedRecovery`, `attemptInputConnectionNullRecovery`).
Wispr re-establishes the input session instead of giving up on a null connection.

## 5. Watchdog / recovery

`AccessibilityWatchdog`, `MainServiceWatchdog`, `AccessibilityRecoveryNotifier`:
if the service or overlay dies, the app nudges the user to re-enable
accessibility / restart, rather than silently failing.

---

## Mapped changes applied to WhisperType-Android

The Gemini Live engine is kept; the overlay/a11y shell is aligned with Wispr.

1. `res/xml/accessibility_service_config.xml`: subscribe to `typeWindowsChanged`
   (the event that drives IME-bounds discovery), enable
   `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents`.
2. `AccessibilityEventRouter`: refresh IME window bounds on
   `TYPE_WINDOWS_CHANGED` in addition to focus/state-changed events.
3. `WhisperTypeAccessibilityService`:
   - `isUsable()` no longer requires validated IME bounds (key + mic + service
     are enough to be "usable").
   - `showDock` no longer gates on IME bounds; dock geometry falls back to a
     Wispr-style right-edge position when the keyboard window is unknown, then
     snaps to the keyboard top edge once IME bounds arrive.
   - `onDockTap` accepts a null IME-bounds token (insertion validates at commit).
4. `OverlayGeometryCalculator`: added `dockRectFallback` and
   `voicePanelRectFallback` for the no-IME-bounds case.
5. `DictationCoordinator`: the inserter retries recoverable failures up to 3×
   with 75 ms backoff (mirrors Wispr's retry-before-fallback philosophy) before
   falling back to the existing copy path.
