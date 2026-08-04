# WhisperType Android — Phase 2 Overlay/Insertion Spike: Failure Report

**Status:** BLOCKED / NOT VERIFIED
**Date:** 2026-08-05
**Branch:** `android-cline`
**Target device:** Samsung Galaxy S25 (SM-S921B, product e1sxins), Android 15 (target SDK 36 / min SDK 34), One UI
**Report purpose:** Document precisely what was built, installed, and tested; capture the exact failure; record every fix attempt; mark PRD completion; and record the requirement to replicate Wispr Flow.

---

## 0. Authoritative product instruction & reference artifact

- During PRD creation the **express instruction from the product owner** was: **WhisperType must replicate exactly how the Wispr Flow app operates** (interaction model, bubble, recording surface, insertion).
- The **Wispr Flow reference APKM was supplied** in the project directory for clean-room behavioural/technical reference:
  - `com.wispr.flowapp_2.1.5-142_4arch_6dpi_24lang_b4bb5b7cf7e3649ddda85e9dfa2ca959_apkmirror.com.apkm` (35,869,633 bytes)
  - Per PRD §18/§19 the reference is behavioural evidence only; WhisperType must be original code/assets and must not copy Wispr proprietary source/artwork.
- **Relevant divergence discovered during this spike:** Wispr Flow requests the **\"Display over other apps\" (`SYSTEM_ALERT_WINDOW`)** permission. That is strong evidence Wispr Flow draws its bubble with a **normal overlay window type (`TYPE_APPLICATION_OVERLAY`, 2038)**. Our rebuild instead follows PRD **FR-3**, which mandates **`TYPE_ACCESSIBILITY_OVERLAY` (2032)** and therefore does **not** request `SYSTEM_ALERT_WINDOW`. This window-type choice is the primary suspect for the device failure below and is the key reassessment point.

---

## 1. Executive summary

Phase 2's goal (the first product gate) is: *on a Samsung phone with SwiftKey, a normal text field shows a touchable WhisperType bubble; tapping it inserts static test text into the correct field; the keyboard remains selected.*

**All host-side work is complete and green**: the whole project compiles, **170 unit tests pass**, lint reports **no issues**, and `assembleDebug` produces a valid APK that installs and runs on the phone.

**However, the on-device gate FAILS**: the microphone bubble never appears. Root cause: the `TYPE_ACCESSIBILITY_OVERLAY` window cannot be created on the device — `WindowManager.addView()` throws
`BadTokenException: Unable to add window -- token null is not valid; is your activity running?`
Because the overlay window is never created, no bubble can render, and static insertion was therefore never reached/verified on the phone. (Host tests plus a built APK are, per PRD §11, explicitly **not** overlay/insertion evidence — the physical gate must pass.)

**The product is therefore NOT complete.** Phase 2 does not pass; PRD §9 mandates stopping and reassessing the architecture here before any Gemini/audio work.

---

## 2. What was completed (PRD mapping) — as of this report

Legend: DONE & verified | HOST (done host/build level, NOT device-verified) | NOT-DONE | BLOCKED

| PRD § | Area | Status | Notes |
|---|---|---|---|
| §14 Step 3 | Pure core contracts & reducer | DONE | `core/state/DictationReducer` (33 tests) |
| FR-7 | Transcript selection/validation | DONE | `core/transcript/TranscriptSelector` (25 tests) |
| FR-5 (logic) | PCM16 20 ms framing, bounded queue, amplitude | DONE | `core/audio` (26 tests) |
| FR-6/§12 (logic) | Log redaction | DONE | `core/privacy/LogRedactor` (19 tests) |
| FR-2/§16.4 | Accessibility service + eligibility + security classification | HOST | `platform/accessibility` (34 tests); binds on device but no overlay window |
| FR-3/§16.5 | Persistent overlay host + bubble UI | HOST | `platform/overlay` (33 tests); **fails on device** - addView BadTokenException (token null) |
| FR-8 | Insertion (exactly-once) | HOST | `AccessibilityTargetGateway` implemented; **not reached on device** (no window) |
| §16.1 | Build, lint, unit tests | DONE | `assembleDebug`, `testDebugUnitTest` (170), `lintDebug` all pass |
| §16.1 | Manifest | DONE | Lint green (removed Phase-4 `DictationForegroundService` stub until Phase 4) |
| Phase 2 | **Samsung SwiftKey overlay/insertion spike** | **BLOCKED / FAIL** | This report |
| Phase 3-8 | UI shell, FGS+audio, Gemini, e2e, onboarding, release | NOT-DONE | Gated behind Phase 2 per PRD §9 |

**Total: 170 unit tests, 0 failures, 0 errors; lint: \"No issues found.\"**

---

## 3. Development work performed (commits on `android-cline`)

Pure-core layer (host-tested, all green):

- `54aa727` feat(core-state): `DictationReducer` - PRD §8 state machine (33 tests)
- `402128f` feat(core-transcript): `TranscriptSelector` - FR-7 (25 tests)
- `67398c9` feat(core-audio): PCM16 20 ms framing + bounded queue + amplitude meter - FR-5 (26 tests)
- `97398c7` feat(core-privacy): `LogRedactor` - FR-6/§12 (19 tests)

Phase-2 platform layer (build-green, device-unsuccessful):

- `a8b4ba1` feat(platform-overlay): `PersistentOverlayHost` + bubble/panel Compose UI + pure visibility/placement - FR-3/§16.5 (33 tests)
- `f4b3a0f` feat(platform-accessibility): `WhisperTypeAccessibilityService`, `SecurityClassifier`, `EditorTracker`, `EligibilityMapper`, `AccessibilityTargetGateway` - FR-2/FR-8/§16.4 (34 tests)
- `3d92559` chore(integration): lint gate passes (drop Phase-4 manifest stub; tag forward-declared §17.5 strings)

All work was done in the sub-agent, ownership-boundary style required by PRD §10, with a git commit at every increment.

---

## 4. Installation & device setup performed

Using wireless adb over Tailscale from this VPS-hosted container to the phone (project `docs/WIRELESS_ADB.md` workflow):

```
adb pair 100.127.110.79:<pairing-port> <rotating-code>   # e.g. 42075 / 183304
adb connect 100.127.110.79:35189                          # connect port
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell pm path com.whispertype.android                 # verify
```

Verified install:
```
package:/data/app/.../com.whispertype.android-.../base.apk
versionName=0.2.0 versionCode=2 minSdk=34 targetSdk=36
```

The **WhisperType accessibility service is enabled and bound** on the device (confirmed via `dumpsys accessibility`):
```
Enabled services: {com.whispertype.android/...WhisperTypeAccessibilityService}
Service[label=WhisperType, ..., retrieveInteractiveWindows=true, eventTypes=[TYPE_VIEW_FOCUSED, TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_WINDOWS_CHANGED]]
```

User testing (manual): re-enabled Accessibility, opened a normal text field with SwiftKey - **no bubble appears**.

---

## 5. Root cause analysis (with evidence)

### 5.1 The service binds and runs
Captured (`logcat`, app pid):
```
I/WhisperTypeAccessibility: Accessibility service connected (static-insertion spike)
```

### 5.2 The overlay window fails to be created
Every attach attempt ends in a `BadTokenException`. Sequence of distinct failures as fixes were applied:

**Attempt A (original code: `baseContext.createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`):**
```
W/PersistentOverlayHost: Overlay attach failed
W/PersistentOverlayHost: java.lang.UnsupportedOperationException: Tried to obtain display from a
    Context not associated with one. Only visual Contexts (such as Activity or one created with
    Context#createWindowContext) or ... Context#createDisplayContext are associated with displays.
    at android.app.ContextImpl.createWindowContext ...
```
-> The accessibility service baseContext is a background (non-visual) context with **no display**, so `createWindowContext` cannot be used directly on it.

**Attempt B (fix: wrap in `createDisplayContext(Display.DEFAULT_DISPLAY)` then `createWindowContext(...)`):**
```
I/WindowManager: WindowManagerGlobal#addView, ty=2032, view=ComposeView{... 0,0-0,0} ...
W/PersistentOverlayHost: Overlay attach failed
W/PersistentOverlayHost: android.view.WindowManager$BadTokenException: Unable to add window --
    token null is not valid; is your activity running?
    at android.view.ViewRootImpl.setView(ViewRootImpl.java:2053)
    at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:606)
    at android.view.WindowManagerImpl.addView(WindowManagerImpl.java:167)
    at ...PersistentOverlayHost.performAttach(...)
```
-> Got a display, but the resulting window context still yields a **null token** at `addView`.

**Attempt C (fix: direct `baseContext.getSystemService(WindowManager).addView(...)`, the classic accessibility-overlay pattern):**
```
I/WhisperTypeAccessibility: Accessibility service connected (static-insertion spike)
I/WindowManager: WindowManagerGlobal#addView, ty=2032, view=ComposeView{... 0,0-0,0} ...
W/PersistentOverlayHost: Overlay attach failed
W/PersistentOverlayHost: android.view.WindowManager$BadTokenException: Unable to add window --
    token null is not valid; is your activity running?
```
-> Same `token null`, even with direct `addView` from the service context.

**Attempt D (fix: match the frozen legacy parameter set - explicit density-derived pixel size, drop `FLAG_LAYOUT_NO_LIMITS`, `gravity=TOP|START`, flags `NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN`):**
Builds cleanly; **not deployed/tested** because the product owner asked to stop iterating. Recorded here for completeness; included in the committed code.

### 5.3 Interpretation
- The accessibility service **binds and runs**; eligibility logic and insertion code are sound at the host level (170 tests).
- The blocker is exclusively **window creation for `TYPE_ACCESSIBILITY_OVERLAY (2032)`** on this Samsung/Android 15 build: `WindowManager.addView` reports **token null** and no overlay window is ever added (confirmed by `dumpsys window` showing no WhisperType overlay window; only `MainActivity`).
- This is strongly consistent with the Android 13+ overlay-window token requirements and the divergence from the reference app's windowing approach (see §0): Wispr Flow's request for `SYSTEM_ALERT_WINDOW` implies it uses `TYPE_APPLICATION_OVERLAY (2038)`, a more permissive/reliable path for third-party overlays than the accessibility overlay type mandated by PRD FR-3.

---

## 6. What is still not working / unresolved

1. **Bubble does not appear** on the Samsung device - the `TYPE_ACCESSIBILITY_OVERLAY` window is not created (`BadTokenException: token null`).
2. Consequently **static insertion was never exercised on-device**; `AccessibilityTargetGateway.insert()` exists and is unit-tested but has **no physical-device evidence**.
3. **Keyboard-behaviour / insertion verification** and all of §11.6 manual acceptance remain **NOT RUN**.
4. The correct windowing solution is unresolved: either (a) find the correct API path to create a valid `TYPE_ACCESSIBILITY_OVERLAY` window token on Samsung/Android 15, or (b) reassess per PRD §9 and align with the reference app by using `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` (which would explain the reference's \"Display over other apps\" permission and is the more device-reliable choice).

---

## 7. Recommended next steps (for the product owner)

1. **Decision:** keep PRD FR-3 `TYPE_ACCESSIBILITY_OVERLAY` or reassess to the reference app's likely `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`. Given the product instruction to \"replicate exactly how Wispr Flow operates\" and the reference's own permission request, a **documented reassessment toward the reference windowing model is recommended** (update the decision record; PRD §9 permits stopping and reassessing at this gate).
2. Whether via a valid accessibility-overlay token or the application-overlay type, land ONE working window on the physical S25, then run §11.6 acceptance (bubble visible, tap inserts static text once, SwiftKey preserved).
3. Only after the Phase-2 device gate passes, proceed to Phase 3 (UI shell), Phase 4 (foreground service + audio), Phase 5 (Gemini Live), Phase 6-8.
4. Keep all subsequent Gemini/audio work gated behind this device gate.

---

## 8. Reference logs (excerpts, no secrets)

All excerpts are non-sensitive and contain no transcripts/keys.

window-attach failure (Attempt B/C):
```
I/WhisperTypeAccessibility: Accessibility service connected (static-insertion spike)
I/WindowManager: WindowManagerGlobal#addView, ty=2032, view=ComposeView{... 0,0-0,0}, caller=...PersistentOverlayHost.performAttach:109 ...
W/PersistentOverlayHost: Overlay attach failed
W/PersistentOverlayHost: android.view.WindowManager$BadTokenException: Unable to add window -- token null is not valid; is your activity running?
W/PersistentOverlayHost:   at android.view.ViewRootImpl.setView(ViewRootImpl.java:2053)
W/PersistentOverlayHost:   at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:606)
W/PersistentOverlayHost:   at android.view.WindowManagerImpl.addView(WindowManagerImpl.java:167)
W/PersistentOverlayHost:   at com.whispertype.android.platform.overlay.PersistentOverlayHost.performAttach(...)
```

Accessibility service registration (from device, non-sensitive):
```
Enabled services: {com.whispertype.android/...WhisperTypeAccessibilityService}
Bound services: {Service[label=WhisperType, ... retrieveInteractiveWindows=true, ...]}
```

---

*Prepared as an accurate, evidence-backed record. Host unit tests passing and a built APK are, per PRD §11, not overlay/insertion evidence; the physical Samsung SwiftKey gate remains BLOCKED.*
