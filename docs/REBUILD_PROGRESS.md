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
