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

This is the **same class of failure** the rebuild plan flagged in §2.2 and that the old project recorded in `docs/FAILURES.md` (`ViewTreeLifecycleOwner` crash). The rebuild has not yet cleared it.

Two attempts were made, both failed:

1. **`CompositionLocalProvider`** — provides the owners *inside* the composition. Rejected: Compose's `WindowRecomposer` needs the owners discoverable **before** the composition starts, via the view tree, not the composition locals.
2. **`OverlayComposeContainer` (a `FrameLayout` implementing `LifecycleOwner` + `SavedStateRegistryOwner` + `ViewModelStoreOwner`)** wrapping the `ComposeView` — rejected: Compose's lookup (`WindowRecomposer_androidKt` line 460) does **not** discover owners by the view implementing the interface. It uses the tag-based `ViewTreeLifecycleOwner.get(view)` lookup, which reads tags set by `ViewTreeLifecycleOwner.set(view, owner)`.

The required fix is to call the **`ViewTree*Owner.set()`** APIs on the container view:
- `androidx.lifecycle.ViewTreeLifecycleOwner.set(view, owner)`
- `androidx.savedstate.ViewTreeSavedStateRegistryOwner.set(view, owner)`
- `androidx.lifecycle.ViewTreeViewModelStoreOwner.set(view, owner)`

**Blocker encountered:** these three `set()` APIs do not resolve on the compile classpath in this environment (AndroidX lifecycle `2.10.0`, savedstate `1.4.0`, Compose BOM `2026.06.01`). The classes exist in the runtime jars (`lifecycle-runtime.aar` classes.jar contains `androidx.lifecycle.ViewTreeLifecycleOwner` with `public static void set(View, LifecycleOwner)`), but they are **not exposed on the compile (api) classpath**, so `import androidx.lifecycle.ViewTreeLifecycleOwner` fails with `Unresolved reference`. This is a dependency/visibility problem, not a missing API.

## 4. Next steps (not executed — stopping per protocol)

1. Resolve the `ViewTree*Owner.set()` compile-classpath visibility — most likely by explicitly adding the artifact that exposes them on the api classpath (e.g. `androidx.lifecycle:lifecycle-runtime` directly, or checking whether Compose BOM `2026.06.01` relocated these helpers to a new package/artifact), or by setting the owners via the underlying `view.setTag(R.id.lifecycle_owner, owner)` tags directly.
2. Re-run the Phase 2 Samsung gate once the Compose composition starts without the `ViewTreeLifecycleOwner` crash.
3. Only then proceed to visibility/show-hide cycles, rotation, and insertion gates.

Do **not** add Gemini/audio work (Phase 6) until this gate passes (plan §7 forbidden shortcuts).

## 5. What this failure does NOT invalidate

The Wispr-parity architectural decisions are confirmed on this device:
- `TYPE_APPLICATION_OVERLAY` (2038) + `SYSTEM_ALERT_WINDOW` attaches on Samsung S25 / Android 16. The old `TYPE_ACCESSIBILITY_OVERLAY` / `BadTokenException` failure is resolved.
- The main-process `FlowRuntimeService` foreground service and the `:accessibility`-process split work; typed IPC connects.

The remaining blocker is purely the Compose-owner installation mechanism for a Service-hosted overlay window — exactly the §2.2 defect the plan named, which still needs the `ViewTree*Owner.set()` calls wired through a compile-visible dependency.
