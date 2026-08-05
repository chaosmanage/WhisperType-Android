# Rebuild Failure Report — Phase 2 Physical Gate (ViewTreeLifecycleOwner crash)

**Date:** 2026-08-05
**Branch / commit:** `rebuild/clean-runtime` @ `b470257` (working tree adds OverlayComposeContainer)
**Device:** Samsung Galaxy S25 (`SM-S921B`), Android 16 (SDK 36), 1080x2340 @ 480dpi
**Keyboard:** SwiftKey (`com.touchtype.swiftkey/com.touchtype.KeyboardService`) — default + enabled
**Status:** **FAIL** — Phase 2 Samsung gate blocked; builder protocol stop.

## 1. What passed (real device evidence)

These are genuine on-device results, not host-side inferences:

- **APK install:** `Success` (`app-debug.apk`, SHA-256 `efea49050cce12a339bf97a1312a384e49957bb18c318037edf42e9969053e36`).
- **Permissions:** `RECORD_AUDIO`, `POST_NOTIFICATIONS` granted; `SYSTEM_ALERT_WINDOW` op = `allow`.
- **`Settings.canDrawOverlays()` gate:** passed (the overlay-permission onboarding started the runtime).
- **`FlowRuntimeService` foreground start:** confirmed — `isForeground=true`, `types=0x00000080` (microphone), notification channel `whispertype_runtime`.
- **Accessibility service in `:accessibility` process:** confirmed — `processName=com.whispertype.android:accessibility`, bound by system (`BIND_ACCESSIBILITY_SERVICE`).
- **Typed IPC link:** confirmed — `FlowRuntimeService: Accessibility process registered its reply messenger`.
- **`TYPE_APPLICATION_OVERLAY` window `addView()` succeeds:** confirmed —
  `WindowManagerGlobal#addView, ty=2038, view=...OverlayComposeContainer, caller=PersistentOverlayHost.performAttach:137` — `addView()` returned **without throwing**. Three `type=2038` WindowTokens present in `dumpsys window`.

  This is the decisive reversal of the prior build's `BadTokenException` (FailureReport.md). The Wispr-parity `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` + service-context window attaches on this Samsung.

## 2. What failed (the blocking crash)

The `TYPE_APPLICATION_OVERLAY` window attaches, but the moment Compose tries to create its composition it throws:

```
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: Process: com.whispertype.android, PID: 1983
E AndroidRuntime: java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from
  com.whispertype.android.platform.overlay.OverlayComposeContainer{d1908c8 ...}
E AndroidRuntime: 	at androidx.compose.ui.platform.WindowRecomposer_androidKt
  .createLifecycleAwareWindowRecomposer(WindowRecomposer.android.kt:460)
E AndroidRuntime: 	at androidx.compose.ui.platform.AbstractComposeView
  .resolveParentCompositionContext(ComposeView.android.kt:331)
E AndroidRuntime: 	at androidx.compose.ui.platform.AbstractComposeView
  .ensureCompositionCreated(ComposeView.android.kt:340)
E AndroidRuntime: 	at androidx.compose.ui.platform.AbstractComposeView
  .attachedToWindow(ComposeView.android.kt:470)
E AndroidRuntime: 	at androidx.compose.ui.platform.AbstractComposeView
  .onAttachedToWindow(ComposeView.android.kt:446)
```

The service then enters a crash-restart loop:
```
W ActivityManager: Scheduling restart of crashed service ...FlowRuntimeService in 1000ms
... (repeats across PIDs 1983, 2152, 2184) ...
W ActivityManager: Scheduling restart of crashed service ...FlowRuntimeService in 3600000ms
```

## 3. Root cause analysis

This is the **same class of failure** the rebuild plan flagged in §2.2 and that the old project recorded in `docs/FAILURES.md` (`ViewTreeLifecycleOwner` crash). The rebuild had not cleared it until the §4 fix.

Two attempts were made, both failed:

1. **`CompositionLocalProvider`** — provides the owners *inside* the composition. Rejected: Compose's `WindowRecomposer` needs the owners discoverable **before** the composition starts, via the view tree, not the composition locals.
2. **`OverlayComposeContainer` (a `FrameLayout` implementing `LifecycleOwner` + `SavedStateRegistryOwner` + `ViewModelStoreOwner`)** wrapping the `ComposeView` — rejected: Compose's lookup (`WindowRecomposer_androidKt` line 460) does **not** discover owners by the view implementing the interface. It uses the tag-based `ViewTreeLifecycleOwner.get(view)` lookup, which reads tags set by `ViewTreeLifecycleOwner.set(view, owner)`.

The required fix is to install the owners on the container view via the **`setViewTree*Owner()` APIs**:
- `setViewTreeLifecycleOwner(view, owner)` (JVM name `ViewTreeLifecycleOwner.set`)
- `setViewTreeSavedStateRegistryOwner(view, owner)` (JVM name `ViewTreeSavedStateRegistryOwner.set`)
- `setViewTreeViewModelStoreOwner(view, owner)` (JVM name `ViewTreeViewModelStoreOwner.set`)

**Diagnosis corrected:** these APIs were **not** missing from the compile classpath (the earlier "dependency/visibility" conclusion was wrong). Inspection of the actual `debugCompileClasspath` and the Kotlin compile-classpath jars (lifecycle `2.10.0`, savedstate `1.4.0`) shows all three classes and their `set()` methods are present and byte-identical to the runtime AARs. The real cause of `import androidx.lifecycle.ViewTreeLifecycleOwner` failing with `Unresolved reference` is a **Kotlin API rename**: in lifecycle 2.10 / savedstate 1.4 the helpers are compiled as **extension functions on `View`** (`View.setViewTreeLifecycleOwner(LifecycleOwner?)`, etc.) whose class-like JVM names (`ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner`, `ViewTreeSavedStateRegistryOwner`) are `@file:JvmName` file-facade artifacts — they are not Kotlin declarations, so importing them as classes cannot resolve. The correct Kotlin usage is `import androidx.lifecycle.setViewTreeLifecycleOwner` / `import androidx.savedstate.setViewTreeSavedStateRegistryOwner` and the extension-function calls. (Verified by decoding the Kotlin metadata of the classes on the compile classpath: `fun setViewTreeLifecycleOwner`, `fun findViewTreeLifecycleOwner`, flags 7.)

## 4. Resolution (executed — pending gate re-run)

Implemented in `app/src/main/java/com/whispertype/android/platform/overlay/OverlayComposeContainer.kt`:

```kotlin
init {
    setViewTreeLifecycleOwner(owners)
    setViewTreeSavedStateRegistryOwner(owners)
    setViewTreeViewModelStoreOwner(owners)
}
```

The tags are set in the container constructor — before `wm.addView()`, hence before the composition starts. No dependency changes were needed. `PersistentOverlayHost`'s stale comment was corrected, and `@SuppressLint("ViewConstructor")` added (the view is programmatic-only; `androidx.annotation.SuppressLint` is not on the KMP compile classpath).

Host gates (2026-08-05, working tree after `ea61f60`):
- `./gradlew :app:assembleDebug` — PASS
- `./gradlew :app:testDebugUnitTest` — PASS (0 failures)
- `./gradlew :app:lintDebug` — PASS

1. Re-run the Phase 2 Samsung gate once the Compose composition starts without the `ViewTreeLifecycleOwner` crash.
2. Only then proceed to visibility/show-hide cycles, rotation, and insertion gates.

Do **not** add Gemini/audio work (Phase 6) until this gate passes (plan §7 forbidden shortcuts).

## 5. What this failure does NOT invalidate

The Wispr-parity architectural decisions are confirmed on this device:
- `TYPE_APPLICATION_OVERLAY` (2038) + `SYSTEM_ALERT_WINDOW` attaches on Samsung S25 / Android 16. The old `TYPE_ACCESSIBILITY_OVERLAY` / `BadTokenException` failure is resolved.
- The main-process `FlowRuntimeService` foreground service and the `:accessibility`-process split work; typed IPC connects.

The remaining blocker was the Compose-owner installation mechanism for a Service-hosted overlay window — exactly the §2.2 defect the plan named. The fix (the `setViewTree*Owner()` extension-function calls in `OverlayComposeContainer`) is now implemented and host-verified; the Phase 2 Samsung gate re-run is outstanding.
