# WhisperType Android — Architecture

This document describes the runtime architecture of WhisperType Android: component ownership, service boundaries, the overlay lifecycle, input-target tracking, the dictation state machine, the communication model, the Gemini flow, insertion safety, and cancellation/session isolation. It is accurate to the code in `app/src/main/java/com/whispertype/android` at version 0.1.0.

## Component ownership

| Component | File(s) | Responsibility |
| --- | --- | --- |
| Application wiring | `WhisperTypeApplication.kt` | Creates the single `SecretStore`, `SensitiveClipboard`, `SettingsRepositoryImpl`, `HistoryRepository`, and `DictationCoordinator`; exposes them as `instance` singletons. |
| Accessibility service | `accessibility/WhisperTypeAccessibilityService.kt` | Tracks the focused editable field and IME window, owns the overlay windows, cancels sessions on focus/IME/screen-off/mic-revoked changes. Exposed process-wide via the `@Volatile shared` companion reference. |
| Target tracking | `accessibility/InputTargetTracker.kt`, `accessibility/InputMethodWindowTracker.kt`, `accessibility/AccessibilityEventRouter.kt`, `accessibility/SecureFieldClassifier.kt` | Convert accessibility events into a `TrackedInput` snapshot and validated IME bounds. |
| Overlay | `overlay/OverlayWindowController.kt`, `OverlayGeometryCalculator.kt`, `OverlayStateRenderer.kt`, `DockedMicOverlay.kt`, `VoicePanelOverlay.kt`, `OverlayTouchPolicy.kt` | Renders the docked mic and the keyboard-covering voice panel as accessibility overlay windows. |
| Dictation engine | `dictation/DictationCoordinator.kt`, `DictationSession.kt`, `DictationState.kt`, `DictationFailure.kt`, `DictationBridge.kt`, `DictationForegroundService.kt` | Orchestrates one recording session: audio capture, Gemini streaming, finalization, insertion, copy fallback. |
| Gemini client | `gemini/GeminiLiveClient.kt`, `GeminiLiveWebSocket.kt`, `GeminiLiveProtocol.kt`, `GeminiTranscriptAssembler.kt`, `GeminiSessionConfig.kt`, `GeminiErrorMapper.kt` | One WebSocket per utterance; setup/audio/activity-end protocol; typed non-sensitive failures. |
| Audio | `audio/AudioCapture.kt`, `AudioChunk.kt`, `AudioQueue.kt`, `WaveformMeter.kt` | 20 ms PCM16 mono capture, bounded FIFO, amplitude meter. |
| Validation | `validation/CandidateSelector.kt`, `TranscriptValidator.kt`, `CleanedTranscriptValidator.kt`, `CorruptionRules.kt`, `HinglishValidator.kt` | Picks cleaned over raw candidates; rejects corrupt or non-Latin output. |
| Security | `security/SecretStore.kt`, `SecretPersistence.kt` (internal), `KeystoreManager.kt`, `SecretRedactor.kt`, `SensitiveClipboard.kt` | AES-GCM-encrypted API key, log redaction, explicit sensitive clipboard writes. |
| Settings | `settings/SettingsRepositoryImpl.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `DockSettings.kt`, `LanguageSettings.kt`, `PrivacySettings.kt` | DataStore-backed settings; speech mode, dock customization, disabled apps, history, onboarding state. |
| Onboarding | `onboarding/OnboardingViewModel.kt`, `OnboardingScreen.kt`, `PermissionLauncher.kt`, `DemoDictation.kt`, `CompatibilityTestScreen.kt` | Step-by-step setup: mic, notifications, accessibility, API key, language, dock preview, demo. |
| History | `history/HistoryRepository.kt`, `HistoryDatabase.kt`, `EncryptedHistoryStore.kt`, `HistorySettings.kt` | Optional encrypted local history (disabled by default; no browsing UI in v0.1). |
| Diagnostics | `diagnostics/DiagnosticsExporter.kt`, `DiagnosticEvent.kt`, `TimingMetrics.kt` | In-memory ring buffer (256 events) of typed, non-sensitive codes and timing aggregates. |

## Service boundaries

Three process-scoped actors exist:

1. **Accessibility service** (`WhisperTypeAccessibilityService`) — the only component that sees the screen. It observes accessibility events, maintains `TrackedInput` and IME bounds, and renders the overlay. It never talks to Gemini or the microphone directly.
2. **Foreground service** (`DictationForegroundService`) — a `microphone`-type foreground service started by `DictationCoordinator.begin()` with the session id as `EXTRA_SESSION_ID`. It shows the recording notification (Stop/Cancel/Open actions routed back through the coordinator) and keeps the session alive. `onTaskRemoved` cancels the session.
3. **Coordinator** (`DictationCoordinator`) — owns the session state machine, audio capture, and Gemini connection. It is created once in `WhisperTypeApplication` and injected into the service/overlay as a `DictationBridge`.

The bridge seam (`DictationBridge`) is the only API the accessibility service and overlay use: `begin(target)`, `stop()`, `cancel()`, `copyResult()`, `dismissCopy()`, plus the `StateFlow<DictationState> state`.

## Overlay lifecycle

`OverlayWindowController` (main thread only) manages two `ComposeView` windows:

- **Dock** — `WRAP_CONTENT` sized circular mic button placed by `OverlayGeometryCalculator.dockRect()` against the keyboard top edge, clamped to the display, honoring position (left/center/right), size (48/56/64 dp), overlap mode, and opacity.
- **Panel** — sized to exactly the IME bounds (`voicePanelRect` = `imeBounds`) and shown for `Starting`, `Listening`, `Finalizing`, `Inserting`, `CopyAvailable`, and `Error` presentations.

The window type is `TYPE_APPLICATION_OVERLAY` (gated by `SYSTEM_ALERT_WINDOW`,
Wispr Flow parity) with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
FLAG_LAYOUT_IN_SCREEN` and `PixelFormat.TRANSLUCENT`, so touches outside the bubble pass through and the overlay never steals focus or dismisses the keyboard. The overlay is a single persistent `WRAP_CONTENT` window whose Compose content is shown/hidden through state; it is added/removed only on service lifecycle or display recovery.

The service's flow collector `combine`s bridge state, dock settings, IME bounds, and active input, then calls `overlayController.update(...)`. The dock is shown only when the presentation says so AND a non-secure `activeInput` exists AND valid IME bounds exist AND the focused package is not in `disabledApps`.

## Input-target tracking

- `AccessibilityEventRouter` dispatches `TYPE_WINDOW_STATE_CHANGED` / `TYPE_VIEW_FOCUSED` to both trackers (and refreshes IME bounds from `getWindows()`), and forwards `TYPE_WINDOW_CONTENT_CHANGED` only for editable sources.
- `InputTargetTracker` resolves the focused editable node (event source, else `findFocus(FOCUS_INPUT)`), skips flag-secure windows entirely, and derives `inputType` from the node class name (class-name markers `password`/`pin` promote to `TYPE_TEXT_VARIATION_PASSWORD`; otherwise `TYPE_CLASS_TEXT` — the accessibility API does not expose the real `EditorInfo` input type). `inputGeneration` increments whenever the focused editor, window, or package changes, and on `invalidate()` and focus loss.
- `SecureFieldClassifier` marks a field secure when the window is flag-secure or the input type carries a password/PIN variation (numeric PIN compared as literal `0x00000020` because the platform removed `TYPE_NUMBER_VARIATION_PIN` from the SDK on API 34).
- `InputMethodWindowTracker` reads only `TYPE_INPUT_METHOD` windows, validates bounds as non-empty, bottom-attached (within 8 px), keyboard-shaped (width >= 66% of display, height 150 px to 60% of display), and debounces geometry (emit when observed twice, after 300 ms settle, or on movement beyond 8 px). No window content is ever read or logged.

## Dictation state machine

`DictationState` (single session at a time, stale sessions rejected by `sessionId`):

```
Unavailable / NoEditableFocus / DockedReady  (idle)
      | begin(token)
      v
Starting -> Listening <-> (stop / 5 min cap / mic lost) -> Finalizing
      |                                                        |
      |                                  (activity-end result) v
      |                                          Inserting
      |                          +---------------------------+-----------+
      v                          v                                       v
  Error (any failure)       Success (auto-dismiss 900 ms)         CopyAvailable
                                                                     (copy or dismiss)
```

- `Unavailable`: accessibility disabled, mic not granted, no API key, or no valid IME layout.
- `NoEditableFocus`: no eligible text field focused.
- `DockedReady`: eligible field focused, keyboard docked, dock visible.
- `Success` auto-returns to the idle state after 900 ms.
- `CopyAvailable` retains the result text until `copyResult()` or `dismissCopy()`.
- `Error` carries a typed, non-sensitive `DictationFailure(code, message, recoverable)`.

## Communication model: @Volatile + StateFlow

All state writes happen on the session coroutine (the injected scope, `Dispatchers.Default` in production), while `begin`, `stop`, `cancel`, `copyResult`, `dismissCopy`, and `refreshReadiness` are invoked from UI or service threads. The design (documented in `DictationCoordinator`):

- `mutableState` is a `MutableStateFlow<DictationState>` — thread-safe by contract; the coordinator never guards it with a lock, and races between session bookkeeping and the UI entry points are resolved in favor of the last writer.
- `@Volatile` fields (`session`, `currentConn`, `currentCapture`, `stopRequested`, `lastChunkAtMillis`, `DictationSession.config/timing/resultConsumed/savedResult`) give stale-but-consistent cross-thread reads without synchronization; the 50 ms ticker loop re-reads them.
- The `DictationSession` itself is not thread-confined: `@Volatile` members are updated from the session coroutine and read from the ticker, collector, and UI/service threads.
- Outbound Gemini messages are marshalled onto a single-thread dispatcher (`wt-gemini-<sessionId>`) so WebSocket state transitions are serialized; events flow back through `MutableSharedFlow(replay = 1, extraBufferCapacity = 64)` so the terminal event survives a slow subscriber.
- `GeminiLiveConnection` (interface in `GeminiLiveClient.kt`) is the seam the coordinator uses; `DefaultGeminiLiveClient` is the OkHttp implementation.

## Gemini flow

1. `begin(target)` -> `Starting` -> start `DictationForegroundService` -> `runSession` on the coordinator scope.
2. Read API key from `SecretStore`; missing key aborts with `API_KEY_MISSING`.
3. `DefaultGeminiLiveClient.connect(sessionId, key, config)` builds `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<encoded>&model=<modelId>` and opens the WebSocket (`modelId` lives only in `GeminiSessionConfig.DEFAULT_MODEL_ID = gemini-3.1-flash-live-preview`).
4. Setup + realtime-input config are the first two messages; any server content before `setupComplete` is a `PROTOCOL_ERROR`.
5. `AudioCapture` streams 20 ms PCM16 mono chunks (16 kHz, 640 bytes) on a daemon thread; each chunk is both pushed to the bounded `AudioQueue` (capacity 100, drops oldest) and sent to Gemini. The ticker updates `Listening` state with elapsed time and amplitude every 50 ms.
6. `stop()` or the 5-minute cap or a 3-second mic stall triggers `doFinalize`: stop capture, drain the queue and send remaining chunks, send exactly one activity-end boundary, wait up to `activityEndTimeoutMillis` (15 s) for `SessionEnd`.
7. `CandidateSelector.select(finalCleaned, finalRaw, languageMode)`: cleaned (if valid per corruption rules, cleaned <= 3x raw length, Latin-only, not a repetition of raw), else raw (if valid, Latin-only), else reject with `NO_VALID_TRANSCRIPT`.
8. Selected text is inserted via the accessibility service; on `InsertionOutcome.Failure` or null the session becomes `CopyAvailable`.

No retry ever happens after audio transmission: a `Failed` event closes the session; the caller starts a new session if appropriate.

## Insertion safety

`AccessibilityInsertionController.insert()` re-validates the live state against the `TargetToken` snapshot captured at session start before committing:

- `SERVICE_GONE` — the same service instance is still the active `shared` reference.
- `SCREEN_LOCKED` — device not locked.
- `PACKAGE_CHANGED` / `DISPLAY_CHANGED` — root window still belongs to the token's package/display.
- `FOCUS_CHANGED` — `currentInputGeneration()` still equals the token's `inputGeneration`.
- `SECURE_FIELD` — the current field is not secure.
- `ALREADY_CONSUMED` — the session id was not already consumed (exactly-once insertion).

A blank result fails with `EMPTY_RESULT`. Failures marked recoverable (`NO_ROOT`, `NO_INPUT_CONNECTION`, `SCREEN_LOCKED`, `EMPTY_RESULT`) route to the `CopyAvailable` fallback; the rest abort. The committer uses `InputMethod.getCurrentInputConnection()` on API 36 (where `AccessibilityNodeInfo.createInputConnection` was removed) and the legacy reflective call on API 34/35.

## Cancellation and session isolation

- Cancellation paths: `DictationCoordinator.cancel()` (from the panel Cancel button, notification action, back press, or service-level triggers) cancels the coordinator job, ticker, and collector; closes the connection and capture; discards any saved result; emits `Cancelled`; and stops the foreground service.
- Service-level triggers (`WhisperTypeAccessibilityService`) cancel the active session on: focus change to a different package/window, IME window loss (after a 500 ms grace), `ACTION_SCREEN_OFF`, and mic permission revoked mid-session.
- `TargetToken.inputGeneration` staleness protects insertion; `consumedSessions` (synchronized set) enforces exactly-once consumption per session id.
- `DictationSession.resultConsumed`/`savedResult` prevent double copy; `copyResult()` consumes once.
- Only one session may be active: `begin()` returns false when a session already exists or the accessibility service is not usable.
