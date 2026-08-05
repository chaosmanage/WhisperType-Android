# Wispr Flow Parity Rebuild — Progress / Status

Builder protocol ledger (`PRD/WisprFlow-Parity-Rebuild-Plan.md` §6). Every entry
records changed files, build id, tests run, and an explicit status. **Physical
device results are NOT RUN** until a Samsung S25 / Android 15 / SwiftKey device is
connected (plan §8 preflight). Compilation / unit tests / lint / APK assembly are
never overlay or insertion evidence.

## Stage 1 — Application overlay runtime (+ accessibility foundations) — DONE (code)

**Physical gate status: NOT RUN** (§8 blocker — no device connected).

Build id: `assembleDebug` on `rebuild/clean-runtime`, commit `4f6ef67`.

### Changed files

- `app/src/main/AndroidManifest.xml` — `SYSTEM_ALERT_WINDOW`; registered
  `FlowRuntimeService` (foreground, main process, `microphone` type); accessibility
  service moved to `android:process=":accessibility"`.
- `app/src/main/res/xml/accessibility_service_config.xml` — added
  `flagInputMethodEditor`.
- `platform/runtime/FlowRuntimeService.kt` — (new) main-process foreground service
  owning the overlay, session state, and the stable Compose owners; binds the
  accessibility process over typed IPC.
- `platform/overlay/PersistentOverlayHost.kt` — `TYPE_APPLICATION_OVERLAY`,
  `WRAP_CONTENT`, Wispr right-edge vertical-center placement, lifecycle-backed
  Compose content, bounded attach retry.
- `platform/overlay/OverlayVisibility.kt` — Wispr placement model (56dp bubble,
  20dp edge margin, right edge).
- `platform/overlay/OverlayOwners.kt` — (new) LifecycleOwner / SavedStateRegistryOwner /
  ViewModelStoreOwner union.
- `platform/ipc/RuntimeIpc.kt` — (new) typed Messenger wire contract.
- `platform/accessibility/WhisperTypeAccessibilityService.kt` — separate-process
  service: binds the runtime, pushes eligibility, handles insert requests,
  implements `onCreateInputMethod()`.
- `platform/accessibility/AccessibilityTargetGateway.kt` — cursor-aware
  `commitText()` via `InputMethod.AccessibilityInputConnection`, replacing
  `ACTION_SET_TEXT`.
- `platform/accessibility/EditorTracker.kt` / `SecurityClassifier.kt` — focus init on
  connect, `TYPE_WINDOWS_CHANGED` focus refresh, `inputType==0` and email/URI/phone
  variations eligible.
- `platform/overlay/OverlayHostFactory.kt` / `DefaultOverlayHostFactory.kt` — new
  signature (serviceContext + owners).
- `MainActivity.kt` — overlay-permission onboarding + runtime start.
- `strings.xml` — runtime label + overlay-permission strings.

### Tests run (host, not physical)

- `./gradlew :app:assembleDebug` — PASS
- `./gradlew :app:testDebugUnitTest` — PASS (117 tests, 0 failures)
- `./gradlew :app:lintDebug` — PASS (0 errors; warnings-as-errors)

### Doc corrections (plan §2.5-§2.8 / §7)

- `docs/WISPR_REVERSE.md` — 2038 is `TYPE_APPLICATION_OVERLAY`, not accessibility
  overlay.
- `docs/ACCESSIBILITY_DESIGN.md` — removed false overlay/insertion verification
  claims; marked physical results NOT RUN.
- `docs/ARCHITECTURE.md`, `docs/BEHAVIOR_MATRIX.md`, `docs/REBUILD_DECISION_RECORD.md`
  — production overlay type corrected to `TYPE_APPLICATION_OVERLAY`.

### Known limitations

- None of the Samsung gates (bubble appears/taps/focus, 20-50 attach cycles,
  insertion, keyboard preservation) have run.
- The cursor-aware `commitText()` path and `flagInputMethodEditor` behavior require
  physical validation (Phase 4 gate).

## Next gates

1. Preflight `adb devices -l`; connect the S25 (§8).
2. Run the Phase 1 Samsung gate (bubble surface, taps, focus, 20 show/hide cycles).
3. Run Phase 2 rotation/50-cycle gate.
4. Run Phase 3/4 focus + insertion gates. Do not proceed to Gemini (Phase 6) until
   the overlay and static-insertion gates pass.

## Stage 1a — Pure insertion verification + build evidence — DONE (code)

- `platform/accessibility/InsertionVerifier.kt` — pure surrounding-text change
  detection (`true` / `false` / `null`-ambiguous), used by the cursor-aware commit.
- `InsertionVerifierTest.kt` — host tests for the exactly-once / ambiguous contract.

**Build identifier:** `rebuild/clean-runtime` @ `b470257`-based working tree,
`app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `efea49050cce12a339bf97a1312a384e49957bb18c318037edf42e9969053e36`.

**Tests run:** `assembleDebug` PASS; `testDebugUnitTest` PASS (0 failures);
`lintDebug` PASS (0 errors). Physical gates remain NOT RUN.

## Stage 1b — First physical run on Samsung S25 / Android 16 / SwiftKey — MIXED

Device preflight (plan §8) passed: `SM-S921B`, Android 16 (SDK 36), 1080x2340 @
480dpi, SwiftKey default. APK installed; `RECORD_AUDIO` / `POST_NOTIFICATIONS`
granted; `SYSTEM_ALERT_WINDOW` op = `allow`.

**PASS (on-device):**
- `FlowRuntimeService` started foreground (microphone type, notification shown).
- Accessibility service running in `:accessibility` process, bound by system.
- Typed IPC connected (`Accessibility process registered its reply messenger`).
- `TYPE_APPLICATION_OVERLAY` (2038) `addView()` returned without throwing — the
  prior `BadTokenException` is resolved. `type=2038` WindowTokens in `dumpsys window`.

**FAIL (on-device) — Phase 2 gate blocked:**
- Compose composition throws `IllegalStateException: ViewTreeLifecycleOwner not
  found from OverlayComposeContainer` the instant the window attaches, then the
  service crash-loops. See `docs/REBUILD_FAILURE_REPORT.md`.

Root cause: the stable Compose owners must be installed via `ViewTree*Owner.set()`
(tag-based lookup), not by the container implementing the owner interfaces or via
`CompositionLocalProvider`. Those `set()` APIs are not currently on the compile
classpath. Next step: expose them via the correct dependency (or set the tags
directly) and re-run the gate.

Builder protocol: **stopped**. Do not add Gemini/audio (Phase 6) until this passes.


