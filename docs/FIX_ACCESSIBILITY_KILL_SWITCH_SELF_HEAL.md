# Accessibility fix: self-healing kill-switch toggle

**Status:** ready to apply to `main` · originally landed as **0.6.2** (versionCode 33) on the
(now-deleted) `feature/compact-keyboard-capsule` branch · **not yet on `main`**.

## Problem

After the user toggles **Settings → General → App enabled** off and back on, the
microphone bubble can stay permanently missing — even though:

- the Home status grid is all green (overlay on, runtime running, Accessibility
  setting on, key/mic/notifications granted), and
- toggling the switch again does not help.

### Root cause

The `:accessibility` process decides whether it is allowed to work by reading the
`app_enabled` setting. It read it through `SettingsRepository.appEnabled` — an
in-process **DataStore flow**:

```kotlin
settings.appEnabled.collect { enabled ->
    cachedAppEnabled = enabled
    if (enabled) bindToRuntime() else { /* unbind */ }
}
```

AndroidX Preferences DataStore only observes writes made **through the same
in-process instance**. The `app_enabled` write happens in the **main process**
(the Settings toggle), so the accessibility process's flow:

- captures the value at its initial read, then
- never sees the main process's subsequent write.

Result: `cachedAppEnabled` stays stuck at `false`. The accessibility service then
behaves exactly like the "disabled" state:

- `onAccessibilityEvent` returns immediately at `if (!cachedAppEnabled) return`
  (no focus/keyboard tracking, no logs),
- it never binds to `FlowRuntimeService` (observed as `hasBound=false` on the
  runtime's service record), so **no eligibility is pushed over IPC**, and the
  bubble never appears,
- the service is only "recovered" when its process restarts (reboot, or a manual
  Accessibility off/on toggle).

## Fix

Make the accessibility process read the **on-disk** value directly (bypassing the
in-process DataStore cache) and self-heal on a timer, so a kill-switch toggle is
always observed within ~2 seconds with no device fiddling.

### 1. `core/settings/PreferencesFileReader.kt` (new, pure, JVM-testable)

Directly parses the Preferences DataStore protobuf file
(`datastore/settings.preferences_pb`) for a boolean key:

```kotlin
object PreferencesFileReader {
    const val DATASTORE_RELATIVE_PATH = "datastore/settings.preferences_pb"
    fun readBoolean(file: File, key: String): Boolean?   // null when missing/unreadable/absent
}
```

Wire format handled:

```
PreferenceMap {
  repeated PreferenceMapEntry preferences = 1;   // length-delimited
}
PreferenceMapEntry {
  string key = 1;
  Value value = 2;                                // length-delimited
}
Value { oneof { bool boolean = 1; ... } }
```

### 2. `data/settings/SettingsRepository.kt`

Expose the stable wire key so the reader and the repository agree:

```kotlin
const val KEY_APP_ENABLED = "app_enabled"
// Keys.appEnabled = booleanPreferencesKey(KEY_APP_ENABLED)
```

### 3. `platform/accessibility/WhisperTypeAccessibilityService.kt`

Replace the fragile flow collector with a poller:

```kotlin
private suspend fun runAppEnabledPoller() {
    val settings = SettingsRepository(this)
    val appEnabledFile = File(filesDir, PreferencesFileReader.DATASTORE_RELATIVE_PATH)
    while (true) {
        val enabled = PreferencesFileReader.readBoolean(appEnabledFile, SettingsRepository.KEY_APP_ENABLED)
            ?: settings.appEnabled.first()          // fallback: DataStore default
        applyAppEnabled(enabled)
        delay(APP_ENABLED_POLL_MS)                  // 2000L
    }
}

private fun applyAppEnabled(enabled: Boolean) {
    val changed = !appEnabledInitialized || enabled != cachedAppEnabled
    cachedAppEnabled = enabled
    appEnabledInitialized = true
    tracker.setAppEnabled(enabled)
    if (enabled) {
        if (runtimeMessenger == null) bindToRuntime()
    } else if (changed) {
        compactController.release()
        runtimeMessenger = null
        try { unbindService(runtimeConnection) } catch (_: Throwable) { /* never bound */ }
    }
}
```

Also removed the one-shot `if (cachedAppEnabled) bindToRuntime()` from
`onServiceConnected` — the poller performs the initial bind (and re-binds on
re-enable).

### Files changed

- `app/src/main/java/com/whispertype/android/core/settings/PreferencesFileReader.kt` (new)
- `app/src/main/java/com/whispertype/android/data/settings/SettingsRepository.kt`
- `app/src/main/java/com/whispertype/android/platform/accessibility/WhisperTypeAccessibilityService.kt`
- `app/src/test/java/com/whispertype/android/core/settings/PreferencesFileReaderTest.kt` (new)

## Verification

### JVM

- `PreferencesFileReaderTest`: boolean round-trip through a real DataStore-written
  file (`true`/`false`), missing file → null, unknown key → null, garbage bytes →
  null, string-valued key → null, multiple booleans in one file.
- Full suite: **503 unit tests pass**, `:app:lintDebug` clean (warnings as errors).

### On-device (0.6.2, before the branch was removed)

After installing 0.6.2 over a stale install and launching:

```
WhisperTypeAccessibility: Runtime service connected over IPC
FlowRuntimeService:        Accessibility process registered its reply messenger
WhisperTypeAccessibility:  Keyboard window probe: hasIme=true …
```

and the runtime service record reported `hasBound=true` — i.e. the poller read
the on-disk `app_enabled=true` and bound to the runtime without any manual
accessibility re-toggle.

## Behavior notes

- Toggling **App enabled** back on recovers the bubble **automatically within
  ~2 s** — no reboot, no Accessibility off/on toggle, no adb.
- While disabled, the app remains genuinely inert (no tracking, no runtime
  binding) exactly as before.
- The Home screen's **"Why is the bubble not showing?"** card reads the *real*
  IPC state (`FlowRuntimeService.currentEligibility`), not the setting; a dead /
  stale accessibility connection surfaces as `service_not_connected`. The logcat
  signal is `Bubble hidden; reasons=[service_not_connected]`.

## Re-apply on `main`

Suggested commits (conventional, per `docs/CONTRIBUTING.md`):

```text
fix(accessibility): self-heal the kill-switch toggle via direct settings-file reads
test(settings): cover PreferencesFileReader against a real DataStore file
```

Plus the standard version bump + `CHANGELOG.md` entry for any behavior change
(0.6.2 was `versionCode 33` / `versionName 0.6.2`) and the doc note below.

## Doc note for CHANGELOG (0.6.2)

> **Self-healing kill switch** — toggling "App enabled" off and back on can no
> longer leave the bubble permanently missing. The accessibility process now
> re-reads the on-disk setting directly every couple of seconds, so re-enabling
> the app restores the bubble on its own, in-app, with no device fiddling.
