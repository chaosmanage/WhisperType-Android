# Gemini Live WebSocket Wire Reference (code-verified)

An exhaustive, code-verified mechanics reference for the Gemini Live WebSocket
(`BidiGenerateContent`) transport as implemented in this repo. Every field name,
message shape, timeout, and state listed below was read directly from the
source. Nothing here is inferred from the public API docs; where a behavior is
observed-on-device rather than proven in code, it is labeled as such.

Companions: `docs/GEMINI_LIVE_TRANSCRIPTION.md` is the high-level engine
overview and `docs/IMPLEMENTATION_PLAN_3.md` documents the 0.4.0 evolution that
this reference was written alongside (docs wave, Phase 9 of plan 3).

This document contains **no real API keys, no transcripts, and no audio
content**. Every payload is shown as a placeholder (`<base64>`, `<text>`, ...).

---

## 1. Endpoint and authentication

The WebSocket endpoint is built by `GeminiSessionFactory.buildWsUrl`
(`platform/gemini/GeminiSessionFactory.kt`):

```kotlin
"wss://generativelanguage.googleapis.com/ws/" +
    "google.ai.generativelanguage.${config.apiVersion}." +
    "GenerativeService.BidiGenerateContent?key=$apiKey"
```

With the default `GeminiSessionConfig.DEFAULT_API_VERSION = "v1beta"` this is:

```
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
```

Key facts:

- The **API key is a query parameter**, appended by `buildWsUrl`. The key is
  provided once per session by `GeminiSessionFactory.create(apiKey, config, client, metrics)`,
  which `require`s it non-empty.
- `OkHttpGeminiLiveSession.redactUrl` strips the key before any log line that
  touches the URL:
  ```kotlin
  url.replace(Regex("key=[^&]*"), "key=<redacted>")
  ```
  `redactUrl` is applied to the `onOpen` log line, so the authenticated URL
  never reaches logcat.
- The key is **never carried in any derived artifact**: `GeminiEvent`s carry
  only typed failures (`DictationFailure`), whose contract explicitly forbids
  keys, URLs, transcripts, editor content, and raw audio; `MutableSessionMetrics.summary()`
  emits only derived durations, counts, and flags; the `SESSION DONE` log and
  the opt-in history record (`FlowRuntimeService.onSessionFinished`) contain
  only the outcome, stage latencies, and (optionally) the settled transcript
  text. There is no code path that forwards the key to the overlay, the
  accessibility process, or the history repository.

---

## 2. Client → server messages (exact JSON)

All builders live in `platform/gemini/GeminiLiveWire.kt`. The codec is pure —
it builds and parses JSON strings but never touches a socket
(`OkHttpGeminiLiveSession` forwards the strings).

### 2.1 `setup` — the mandatory first message (`buildSetup`)

Sent from `onOpen` (the moment OkHttp reports the socket is open, code 101). The
keys are built in this exact order by `buildJsonObject` (insertion-ordered):

```json
{
  "setup": {
    "model": "models/<config.model>",
    "generationConfig": {
      "responseModalities": ["AUDIO"]
    },
    "inputAudioTranscription": {},
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "disabled": true
      }
    },
    "systemInstruction": {
      "parts": [ { "text": "<text>" } ]
    }
  }
}
```

Per-key detail (from `GeminiLiveWire.buildSetup` and
`GeminiSessionConfig`):

| Key | Value | Condition |
| --- | --- | --- |
| `model` | `"models/${config.model}"` — e.g. `"models/gemini-3.1-flash-live-preview"` (the factory `DEFAULT_MODEL`) | always |
| `generationConfig.responseModalities` | `["AUDIO"]` (the config default `responseModalities = listOf("AUDIO")`) | always |
| `inputAudioTranscription` | `{}` — empty object | only when `config.inputAudioTranscription` (default `true`) |
| `outputAudioTranscription` | `{}` — empty object | only when `config.outputAudioTranscription` (default `false`, so **absent in production**) |
| `realtimeInputConfig.automaticActivityDetection.disabled` | `true` | only when `config.automaticActivityDetectionDisabled` (default `true`) |
| `systemInstruction.parts[].text` | the instruction text | only when `config.systemInstruction != null` |

**No `languageCode`.** The Live API rejects a `languageCode` field on
`inputAudioTranscription` with the verbatim error `"unknown name language code"`
(recorded during development and repeated in `GeminiLiveWire` and
`LanguageMode` doc comments). Language and script bias therefore travel in the
`systemInstruction`, never in a structured field.

**`systemInstruction` mapping.** `FlowRuntimeService` builds the config with
`systemInstruction = language.liveInstruction(cachedPolishLevel)` where
`cachedPolishLevel` is one of the four `TranscriptionStyle` values. The mapping
(`core/model/LanguageMode.kt`, `liveInstruction(style)`):

Style text, per `TranscriptionStyle`:

- **NONE** — "Transcribe the user's speech verbatim, exactly as spoken. Do not
  add or change punctuation, capitalization, grammar, or wording. Output only
  the words spoken."
- **LOW** — "Transcribe the user's speech with light cleanup: add basic
  sentence punctuation and capitalization, but keep the exact words and natural
  spoken phrasing."
- **MEDIUM** — "Transcribe the user's speech into clean written text: proper
  punctuation, capitalization, and standard grammar, while keeping the user's
  words and meaning."
- **HIGH** — "Transcribe the user's speech into polished, well-structured
  written text: correct grammar, proper punctuation and capitalization, clear
  sentence structure, and logical organization, with paragraphs and lists where
  appropriate. Keep the user's meaning and words wherever possible."

Per `LanguageMode`:

| `LanguageMode` | `style` | instruction sent |
| --- | --- | --- |
| `ENGLISH` | `NONE` | **null — no `systemInstruction` at all** |
| `ENGLISH` | `LOW` / `MEDIUM` / `HIGH` | the style text only |
| `HINGLISH` | any | style text + Hinglish rule + `"Output only the transcription, nothing else."` |

The Hinglish rule appended for every style:

> "Write Hindi words in Latin script (romanized Hindi / Hinglish), never in
> Devanagari script. Keep English words and phrases exactly as spoken."

So English + `NONE` yields no instruction key; **Hinglish always carries the
Latin-script rule** regardless of style.

### 2.2 Realtime activity boundaries and audio frames

Push-to-talk manual activity signaling (the production design —
`automaticActivityDetection.disabled = true`):

- **Start of utterance** (`buildActivityStart`), sent from `startActivity` on
  the `Ready -> ActivityStarted` transition, before the first audio frame:
  ```json
  { "realtimeInput": { "activityStart": {} } }
  ```
- **End of utterance** (`buildActivityEnd`), sent from `endActivity` on the
  `ActivityStarted -> ActivityEnded` transition, after the final audio frame:
  ```json
  { "realtimeInput": { "activityEnd": {} } }
  ```
- **Audio frame** (`buildAudioChunk`):
  ```json
  {
    "realtimeInput": {
      "audio": {
        "data": "<base64>",
        "mimeType": "audio/pcm;rate=16000"
      }
    }
  }
  ```
  `data` is base64 of raw PCM16 (16-bit signed little-endian, mono, 16 kHz —
  `GemAudioFormat.SAMPLE_RATE_HZ = 16000`, `CHANNELS = 1`, `ENCODING_PCM_16BIT`).
  Each frame is exactly **640 bytes** (`FRAME_MILLIS=20` ms: `20/1000 * 16000 * 2`
  via `GemAudioFormat.bytesPerFrame` and `AudioChunk.expectedByteCount`). The
  base64 + JSON + queue work runs on `Dispatchers.IO` inside `sendAudio`.

### 2.3 What is NEVER sent (removed or unused)

| Message | Builder | Status |
| --- | --- | --- |
| `clientContent.turnComplete` (`{"clientContent":{"turnComplete":true}}`) | `buildTurnComplete` | **Never sent.** The builder still exists in the codec and is covered by a unit test, but no caller invokes it — the dictation prime (`sendTextTurn`/text turns, `clientContent.turns`) was deleted in Release B. |
| `realtimeInput.audioStreamEnd` (`{"realtimeInput":{"audioStreamEnd":true}}`) | `buildAudioStreamEnd` | **Not used in production.** It is the completion boundary for the automatic-VAD variant; `endActivity` selects it only when `automaticActivityDetectionDisabled == false`. The default config is `true` (manual), so the code path is dead in production. |
| `outputAudioTranscription` | — | **Off by default** (`GeminiSessionConfig` default `false`), so the setup object simply omits the key. |
| `languageCode` | — | **Never sent** — the API rejects it (see §2.1). |

---

## 3. Server → client messages (exact JSON + parse mapping)

Every server frame is decoded to UTF-8 and fed through
`GeminiLiveWire.parseServerMessage`, which routes on the **root** keys:

```kotlin
root.containsKey("setupComplete") -> SetupComplete
root.containsKey("setupError")    -> parseSetupError   -> SetupError(message)
root.containsKey("error")         -> parseTopLevelError -> SetupError(message)
root.containsKey("serverContent") -> parseServerContent -> ServerContent(...)
root.containsKey("goAway")        -> GoAway
else                              -> Unknown(raw)
```

Parse failures (any `Throwable`) fall back to `ServerMessage.Unknown(raw)`,
which is discarded by the session. `Json` is configured with
`ignoreUnknownKeys = true` and `isLenient = false`.

| Server payload | Parsed into | Behavior |
| --- | --- | --- |
| `setupComplete` | `ServerMessage.SetupComplete` | The **readiness gate**: `state: Connecting -> Ready`, `ready.complete(Unit)`, emits `GeminiEvent.Ready`. Only after this may audio be sent (`awaitReady`). |
| `setupError.error.message` | `ServerMessage.SetupError(message)` | Typed **`gemini_setup`** failure — `ready.completeExceptionally`, emits `GeminiEvent.Failed`. |
| top-level `error.message` | `ServerMessage.SetupError(message)` | Same typed `gemini_setup` failure as above. |
| `serverContent.inputTranscription.text` | `inputTranscription: String?` | **THE dictation source.** Emits `GeminiEvent.TranscriptCandidates([ResultCandidate(raw = it, cleaned = null, language = config.language)])` and counts `inputTranscriptionCount`. |
| `serverContent.outputTranscription.text` | `outputTranscription: String?` | **Diagnostics only** — increments `outputTranscriptionCount`; its text is **never selected** as a candidate and never logged. |
| `serverContent.modelTurn.parts[].text` | `textParts: List<String>` | Parsed **for compatibility only**; never a candidate. The voice-only model runs AUDIO modality so this is effectively empty. |
| `serverContent.turnComplete` | `turnComplete: Boolean` | Lifecycle flag: sets `metrics.turnCompleteArrived = true`, emits `GeminiEvent.TurnComplete` (retained even when it precedes STOP — Release E7). |
| `serverContent.interrupted` | `interrupted: Boolean` | Lifecycle flag, parsed and carried on `ServerContent`; no dedicated action. |
| `goAway` | `ServerMessage.GoAway` | Emits `GeminiEvent.SessionEnd`. |
| anything else | `ServerMessage.Unknown(raw)` | Ignored. |

The **critical binary-frame fact**: the Gemini Live server sends every
server→client message as a **binary WebSocket frame (opcode 0x2)**. OkHttp
therefore routes them to the byte overload, and
`OkHttpGeminiLiveSession` has:

```kotlin
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    onMessage(webSocket, bytes.utf8())
}
```

Both overloads decode through the same parser. **This binary callback must
never be removed** — without it every server message would be silently dropped
and the session would hang at `Connecting` until the 15 s ready timeout.

---

## 4. Transport lifecycle

### 4.1 Connection and keep-alive

- The OkHttp WebSocket is created with `Request.Builder().url(wsUrl)` in the
  `OkHttpGeminiLiveSession` constructor; the handshake completes with an
  **HTTP 101 upgrade** (`onOpen` logs `code=101`).
- `GeminiSessionFactory.defaultClient`:
  ```kotlin
  OkHttpClient.Builder()
      .pingInterval(Duration.ofSeconds(20))
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build()
  ```
  - **`pingInterval` = 20 s** — OkHttp sends a keep-alive ping every 20 s. The
    20 s pong window is observed in the wild as
    `onFailure "sent ping but didn't receive pong within 20000ms"` (OkHttp's
    standard timeout; mapped to a `gemini_transport` failure).
  - **`readTimeout` = 0** (infinite) — a server that streams nothing must not
    be killed by the read timeout while idle.
  - One long-lived client is shared service-wide (`FlowRuntimeService.sharedOkHttpClient`);
    its dispatcher/connection pool is never shut down per session.
- Observed close codes (on-device, recorded in `IMPLEMENTATION_PLAN_3.md`):
  - **1000** — normal close (`OkHttpGeminiLiveSession.close()` sends
    `close(NORMAL_CLOSE_CODE, "session closed")`).
  - **1008** — policy violation, seen as `"The operation was aborted"` and as
    invalid-API-key responses in tests (e.g. `1008 "API key not valid..."`).

### 4.2 Readiness gate

`awaitReady()` is the **`setupComplete`** gate, not socket-open:

```kotlin
withTimeout(READY_TIMEOUT_MS) { ready.await() }
```

- `READY_TIMEOUT_MS = 15_000`. On timeout: `GeminiLiveException` with
  `gemini_setup` / `"Timed out waiting for the Gemini session to start."`,
  `recoverable = true`.
- `onClosing`/`onClosed` **before** `ready` completes also fail the gate with
  `gemini_setup` (reason text, or `"Connection closed (code N) before setup."`).
- `onFailure` completes the gate exceptionally with the raw throwable and (if
  the session was not already `closed`) emits `GeminiEvent.Failed(gemini_transport)`.

### 4.3 Warm-pool lifecycle (`WarmLiveSessionManager`)

Eligibility-driven prewarm (focused non-secure editor, keyboard visible, mic +
API key configured, no active dictation — `FlowRuntimeService.computeWarmEligibility`):

- States: `None -> Connecting -> Ready(session) -> Claimed -> Closing`, with
  `Backoff(retryMs)` between failed attempts.
- **Reconnect backoff: 1 s, 2 s, 5 s, then 10 s** (`Config.backoffStepsMs`); the
  last step repeats (`getOrElse ... last()`), with `backoffAttempt` reset to 0
  on success.
- **Idle timeout: 30 s** (`Config.warmIdleTimeoutMs`); when it fires, the warm
  session is closed and the pool returns to `None`. A `Claimed` session is never
  closed by the manager — it belongs to an active dictation.
- `claim()` atomically takes a `Ready` session, cancels the idle timer, sets
  `Claimed`, and starts a replacement prewarm. On a claim the coordinator sees
  `SessionResolution(ready = true)` and skips cold connect/buffering.

### 4.4 Close and the transport state machine

```kotlin
private enum class State { Connecting, Ready, ActivityStarted, ActivityEnded, Closed }
```

Driven by one `AtomicReference`, so concurrent callers (audio sender + finalizer)
cannot violate it. Transition rules enforced in `startActivity` / `sendAudio` /
`endActivity` (rejection reasons are the `SendResult.Rejected` strings):

| Call | Allowed from | Rejected from |
| --- | --- | --- |
| `startActivity` | `Ready` (CAS → `ActivityStarted`, sends `activityStart`) | `Connecting` → `session_not_ready`; `ActivityEnded` → `activity_ended`; `Closed` → `socket_closed`. Duplicate start in `ActivityStarted` → `Accepted`, **no second wire message**. |
| `sendAudio` | `ActivityStarted` only | `Connecting` → `session_not_ready`; `Ready` → `audio_before_activity_start`; `ActivityEnded` → `audio_after_activity_end`; `Closed` → `socket_closed`. |
| `endActivity` | `ActivityStarted` (CAS → `ActivityEnded`, sends `activityEnd`) | `Connecting` → `session_not_ready`; `Ready` → `activity_not_started`; `Closed` → `socket_closed`. Duplicate end in `ActivityEnded` → `Accepted`, **no second wire message**. |
| `close` | **any state** | never — idempotent (`AtomicBoolean` compare-and-set) |

`close()` is idempotent: it CASes `closed`, forces `State.Closed`, sends
`close(1000, "session closed")`, nulls the socket, completes the ready deferred
exceptionally, and closes the event channel. A failed `WebSocket.send` on any
path returns `SendResult.Rejected` immediately and forces `State.Closed` —
callers never wait for a completion message that can never arrive.

---

## 5. Full session timeline

### 5.1 Wire-order invariant

For one dictation turn, message order is always:

```
setup -> setupComplete -> activityStart -> ordered audio frames -> activityEnd
```

The coordinator guarantees this by (a) sending `setup` in `onOpen` before
anything else, (b) gating the first audio on `awaitReady()` (the `setupComplete`),
(c) opening the activity before the first frame, and (d) draining the producer
and **joining the audio sender** before `endActivity` is sent
(`startFinalization`: `requestStop` → `awaitQuiescence` → `audioJob.join` →
`CaptureQuiesced` → `endActivity`).

### 5.2 Monotonic stage list (`MutableSessionMetrics.Event`)

All timestamps use the injectable monotonic clock (`nowNanos`), never wall
clock. `mark(event)` records the **first** timestamp for each event; later marks
are ignored. The enum, in definition order, and the recording point of each:

| Event | Recorded by | Where |
| --- | --- | --- |
| `Tap` | `DictationCoordinator.start()` | the accepted tap on the bubble |
| `KeyLoadStarted` | `FlowRuntimeService.resolveSession` | start of key resolution |
| `KeyLoaded` | `FlowRuntimeService.resolveSession` | key provider returned (warm claim or cold key load) |
| `SettingsReady` | `FlowRuntimeService.resolveSession` | runtime settings snapshot resolved |
| `SocketCreated` | `FlowRuntimeService.resolveSession` | warm claim or `GeminiSessionFactory.create` |
| `SocketOpen` | `OkHttpGeminiLiveSession.onOpen` | HTTP 101 upgrade |
| `SetupComplete` | `OkHttpGeminiLiveSession.onMessage` | `setupComplete` received |
| `CaptureStartRequested` | `FlowRuntimeService.startCapture` | before `AudioRecord` construction |
| `CaptureStarted` | `FlowRuntimeService.startCapture` | `AudioStartResult.Started` |
| `ActivityStartQueued` | `OkHttpGeminiLiveSession.startActivity` + coordinator | `activityStart` enqueued on the socket |
| `FirstAudioQueued` | `DictationCoordinator.sendChunk` | first accepted audio frame |
| `FirstInputTranscript` | `OkHttpGeminiLiveSession.onServerContent` | first non-empty `inputTranscription` |
| `Stop` | `DictationCoordinator.stop()` / `cancel()` | user STOP/CANCEL (and auto-stop) |
| `CaptureQuiesced` | `DictationCoordinator.startFinalization` | producer drained, sender joined |
| `LastAudioQueued` | `DictationCoordinator.streamAudio` | capture channel closed, final frame sent |
| `ActivityEndQueued` | `OkHttpGeminiLiveSession.endActivity` + coordinator | `activityEnd` enqueued |
| `TurnComplete` | `OkHttpGeminiLiveSession.onServerContent` | `serverContent.turnComplete` seen |
| `TranscriptSettled` | `DictationCoordinator.settle` | selection or failsafe resolution |
| `InsertionRequested` | `DictationCoordinator.insertSettled` | insert IPC handed to accessibility |
| `InsertionResult` | `DictationCoordinator.onInsertionResult` | insert result routed by session id |

Derived duration accessors: `tapToCaptureMs`, `tapToSetupCompleteMs`,
`tapToFirstAudioQueuedMs`, `firstAudioToFirstTranscriptMs`,
`stopToCaptureQuiescedMs`, `stopToActivityEndQueuedMs`, `stopToTurnCompleteMs`,
`stopToSettledMs`, `stopToInsertionResultMs`, `insertionRequestedToResultMs`.

### 5.3 Decoding a `SESSION DONE` line

At the terminal state `FlowRuntimeService.onSessionFinished` logs exactly one
line (`metrics.summary()`, joined tokens, no transcript/audio/keys):

```
SESSION DONE outcome=Success tapToCapture=105ms setup=718ms tapToFirstAudio=728ms
firstAudioToFirstTranscript=7861ms stopToQuiesce=65ms stopToActivityEnd=65ms
stopToTurnComplete=65ms stopToSettled=553ms stopToInsert=608ms insertToResult=49ms
captured=393 accepted=393 rejected=0 maxQueue=1858 inputTx=1 outputTx=0
turnComplete=false hardDeadline=false overflow=false [reject=DEVANAGARI] [lenient=true]
```

Field by field (`MutableSessionMetrics.summary()` order):

| Token | Meaning |
| --- | --- |
| `outcome=Success` | `DictationState` class name (`Success`, `Error`, `Cancelled`, ...) |
| `tapToCapture=105ms` | `tapAt -> captureStartedAt` |
| `setup=718ms` | `tapAt -> setupCompleteAt` |
| `tapToFirstAudio=728ms` | `tapAt -> firstAudioQueuedAt` |
| `firstAudioToFirstTranscript=7861ms` | `firstAudioQueuedAt -> firstInputTranscriptAt` |
| `stopToQuiesce=65ms` | `stopAt -> captureQuiescedAt` |
| `stopToActivityEnd=65ms` | `stopAt -> activityEndQueuedAt` |
| `stopToTurnComplete=65ms` | `stopAt -> turnCompleteAt` (absent if `turnCompleteArrived=false`) |
| `stopToSettled=553ms` | `stopAt -> transcriptSettledAt` |
| `stopToInsert=608ms` | `stopAt -> insertionResultAt` |
| `insertToResult=49ms` | `insertionRequestedAt -> insertionResultAt` |
| `captured=393` | PCM frames captured by the producer |
| `accepted=393` | frames accepted by `sendAudio` |
| `rejected=0` | frames rejected by the session state machine |
| `maxQueue=1858` | peak `WebSocket.queueSize()` (`recordWebSocketQueue`) |
| `inputTx=1` | `serverContent.inputTranscription` messages seen |
| `outputTx=0` | `serverContent.outputTranscription` messages seen |
| `turnComplete=false` | whether `serverContent.turnComplete` arrived |
| `hardDeadline=false` | settlement came from the 3 s deadline (vs the debounce) |
| `overflow=false` | pre-ready audio buffer never overflowed |
| `[reject=DEVANAGARI]` | first selector `RejectionRule` that blocked a candidate (only when selection failed) |
| `[lenient=true]` | a rejected candidate was still inserted by the lenient fallback (only when it was) |

The example above: a warm session, ~3.9 s of audio, the server delivered one
input transcription ~7.9 s after the first frame, STOP settled via the debounce
(~553 ms) and the insertion round-trip took ~49 ms — but the candidate was
initially rejected (Devanagari in English mode) and rescued by the lenient
fallback.

---

## 6. Error taxonomy

Every typed failure (`DictationFailure`) in the codebase, with its exact
trigger, `recoverable`, `retryAllowed`, and user-visible `message`. Unless
noted, `retryAllowed = recoverable` was set explicitly.

| code | Trigger | recoverable | retryAllowed | User-visible message |
| --- | --- | --- | --- | --- |
| `gemini_setup` | `awaitReady` 15 s timeout; `setupError.error.message`; top-level `error.message`; `onClosing`/`onClosed` before ready; coordinator fallback when `awaitReady` throws a non-`GeminiLiveException` | true | true | session: the server/timing detail; coordinator fallback: `"Could not reach Gemini. Check your network and API key."` |
| `gemini_transport` | (a) `onFailure` — pong timeout, network drop (`t.message ?: "Connection failed"`); (b) `SessionEnd` in the coordinator → `"The Gemini session closed."`; (c) rejected activity boundary → `"Gemini rejected the activity boundary (<reason>)."` | true | (a)/(c) **true**, (b) **false** (default; the turn ended itself) | as above |
| `gemini_no_transcript` | settlement with no usable value at the hard deadline | true | true | `"No transcript could be recognized. Try again."` |
| `gemini_connection_too_slow` | `PreReadyAudioBuffer` overflow (150 frames ≈ 3 s while connecting) — overflow is never silently dropped | true | true | `"The Gemini connection is too slow. Try again."` |
| `runtime_no_api_key` | `keyProvider.provideKey()` returned null/empty in `resolveSession` or `createColdSession` | true | false | `"Add your Gemini API key in Settings first."` |
| `runtime_mic_permission` | `RECORD_AUDIO` not granted at `startCapture` | true | false | `"Microphone permission was revoked."` |
| `runtime_no_accessibility` | `sendInsertion` returned false (`a11yReply == null`, i.e. accessibility process not registered) | true | true | `"Could not reach the accessibility service."` |
| `insert_ambiguous` | `InsertionResult.Ambiguous` (commit could not be confirmed) | true | false | `"Could not confirm the text was inserted. Use Copy to grab it."` |

Also present but not in the PRD taxonomy: `gemini_events` — the
`runSession` catch-all when the session stops unexpectedly
(`"The Gemini session stopped unexpectedly."`, `recoverable = true`,
`retryAllowed = false`). Insertion failures arriving via IPC
(`InsertionResult.Failed`) carry whatever code the accessibility process packed
(`RuntimeIpc.KEY_FAILURE_*`).

**Retryable-error persistence.** In `DictationCoordinator.fail`:
`if (failure.retryAllowed) return` — the `DictationState.Error` is left on
screen and persists until the user acts. Non-retryable failures auto-reset to
`Idle` after `returnToIdleMs` (1200 ms).

**Retry / Dismiss overlay actions** (`FlowRuntimeService.onOverlayIntent`):
- `RETRY` → `coordinator.retry()` — accepted only while `lastPublished is
  DictationState.Error`; it clears the errored holder (`resetToIdle`) and starts
  a fresh session; otherwise returns false.
- `DISMISS` → `coordinator.dismiss()` — clears the Error panel and returns to
  `Idle`.
- `CANCEL` → `coordinator.cancel()` — marks `Cancelled(USER)` and tears down.
- `COPY` → no-op (`Unit`).

---

## 7. Settlement internals

### 7.1 `TranscriptAccumulator` merge rules

`accept(message)` keeps **one cumulative current value** and bumps
`revisionCount` on every accepted change. Priority (exact code order):

1. **Blank/whitespace** message (`message.trim().isEmpty()`) → ignored, no bump.
2. **Exact duplicate** (`message == current`) → ignored, no bump.
3. **No current yet** (`current == null`) → accepted as-is.
4. **Cumulative extension** (`isCumulativeExtension`) → merged; the longer
   cumulative value wins. Rule: `message.startsWith(current)` AND the character
   right after `current` is whitespace OR `current` itself already ends on a
   trailing whitespace. Fragments are never trimmed before this check, because
   internal spaces can carry word boundaries.
5. **Reverse prefix** (`current.startsWith(message)`, a shorter/partial
   revision) → ignored; the longer cumulative value wins.
6. **Otherwise** (correction/replacement of a prior provisional value) →
   replaced.

### 7.2 `TranscriptSelector` trust policy + `RejectionRule`

The selector trusts the user's own ASR speech (`inputTranscription`) and only
rejects content that can never be usable dictation. `select(candidates)` does a
two-pass left-to-right scan: pass 1 finds the first candidate whose **cleaned**
text passes (and is not implausibly expanded); pass 2 (only if pass 1 fails)
finds the first candidate whose **raw** text passes. `cleaned` wins over `raw`;
within a tier, the earliest candidate wins. No invalid text is ever returned
(`TranscriptSelection.None` has no text — accessing it throws).

`reject(text, language)` fires, in priority order:

| `RejectionRule` | Condition |
| --- | --- |
| `BLANK` | `text.isBlank()` |
| `PUNCTUATION_ONLY` | no `isLetterOrDigit()` character |
| `GARBLED` | > 0 replacement (`\uFFFD`) / control chars and their ratio exceeds `GARBLED_CHAR_RATIO = 0.2` |
| `DEVANAGARI` | any Devanagari block char (`\u0900..\u097F`, `\uA8E0..\uA8FF`, `\u1CD0..\u1CFF`) **and** `language == LanguageMode.ENGLISH` |

Hinglish mode **accepts** Devanagari (prefer inserting it over erroring).
`diagnose(candidates)` mirrors `select`'s two-pass order and returns the first
`RejectionDiagnosis(rule, wordCount, charCount, hasDevanagari)` that would make
selection fail — logging only, never transcript text. A cleaned candidate is
also rejected when implausibly expanded (`cleanedWords > 10 * rawWords`, the one
remaining model-output heuristic). URLs, emails, identifiers, numbers, natural
punctuation, and code-switching survive because only the four rules above fire.

### 7.3 Settlement timing and failsafes (`DictationCoordinator`)

- **3 s absolute hard deadline** (`Config.hardDeadlineMs`) — a single monotonic
  timer from STOP (`deadlineJob`); at fire, if still `Finalizing`, sets
  `metrics.usedHardDeadline = true` and settles. **It is never extended.**
- **250 ms settle debounce** (`Config.settleDebounceMs`) — reset by every new
  input transcript revision while finalizing, by `turnComplete` (when a valid
  transcript exists), and by `afterActivityEnd` when `turnCompleteSeen` +
  valid transcript are both present. `restartSettleDebounce` cancels/rearms and
  can never push the deadline.
- **Provisional-fragment rule** — at the hard deadline, a selection shorter
  than 2 trimmed characters (`isClearlyProvisional: text.trim().length < 2`,
  e.g. `"t"` from `"the"`) is rejected with `gemini_no_transcript` rather than
  inserted.
- **Lenient fallback** (`lenientAccept`) — when strict selection returns
  `None`, the source is still the user's own speech, so it is inserted unless:
  blank / no letter-or-digit, `diagnosis.rule == GARBLED`, or a fragment < 2
  chars at the hard deadline. `metrics.usedLenientFallback = true` when it
  fires.
- Settlement path: `accumulator.settledText()` → `ResultCandidate(raw, null, language)`
  → `selector.select` → `Cleaned`/`Raw` → insert (or provisional reject), or
  `None` → lenient fallback → insert, else `failNoTranscript` (`gemini_no_transcript`,
  retryable).

---

## 8. State machines

### 8.1 Transport machine (per session, `OkHttpGeminiLiveSession`)

```
                        startActivity (sends activityStart)
        onOpen:             |             endActivity (sends activityEnd)
         setup     setupComplete      +---------------+   close
   Connecting --------→ Ready -------→ ActivityStarted ------→ ActivityEnded
        +                |  |              |                     |
        |                |  +--------------+  duplicate: no wire  |
        +---- close ----+  sendAudio (frames)  message            |
                                                            close |
                                                             +----+----+
                                                             v         v
                                                              Closed
```

Rules: `startActivity` before `Ready` → `session_not_ready`; audio before
`startActivity` → `audio_before_activity_start`; audio after `endActivity` →
`audio_after_activity_end`; `endActivity` before start → `activity_not_started`;
duplicate start/end → `Accepted` with no second wire message; a failed
`WebSocket.send` forces `Closed`; `close()` is idempotent from any state.

### 8.2 Dictation lifecycle (per overlay turn, `DictationState`)

```
            start() (tap)                    stop() / auto-stop
   Idle ───────────────→ Starting ─────────────→ Listening ──────────→ Finalizing
                          │                        │  │                   │
                          │                    cancel()                settle()
                          │                        │  │                   │
                          │  ┌─────────────────────┘  │   insertSettled    v
                          │  v                        v   ──────────────→ Inserting
                          │ Cancelled(reason=USER)    │                   │
                          │                          │        IPC result  v
                          │                          │        ┌─────→ Success
                          └──────────────────────────┘        └─────→ Error(insert_ambiguous / Failed)
                                                          (retryable Error persists
                                                           until Retry / Dismiss)
```

`DictationState` variants: `Unavailable`, `Idle`, `Starting(sessionId, target)`,
`Listening(sessionId, amplitude, elapsedMillis, connecting)`,
`Finalizing(sessionId)`, `Inserting(sessionId)`, `Success(sessionId)`,
`Cancelled(sessionId, reason)`, `CopyAvailable(sessionId, candidate)`,
`Error(sessionId, failure)`. `cancel()` publishes `Cancelled(USER)`, marks
`Stop`, and tears down; the overlay returns to `Idle` after `returnToIdleMs`.

**0.4.0 auto-stop** (`DictationCoordinator.runAutoStop`): while `Listening`, a
watcher ticks every `AUTO_STOP_CHECK_MS = 200` ms and stops on whichever fires
first — a **silence timeout** (`Config.autoStopSeconds`, reset to 0 whenever
`capture.amplitude.value >= speechAmplitudeThreshold = 0.02f`) or a **hard
recording cap** (`Config.maxRecordingSeconds`). Both end through the same
`stop()` path as a user STOP. The runtime feeds the settings-backed product
default (60 s) into both knobs (`FlowRuntimeService` config wiring); disabled
when the value is 0.

---

## 9. Module interaction (sequence)

ASCII sequence for one full dictation turn (warm claim shown; cold path differs
only in `resolveSession` + pre-ready buffering):

```
 Bubble tap
   │  OverlayIntent.START_DICTATION
   ▼
 FlowRuntimeService.onOverlayIntent ──► DictationCoordinator.start()        [metrics: Tap]
   │                                        │
   │                                        │ runSession(holder)
   │                                        ▼
   │                     DictationHost.resolveSession(metrics)              [KeyLoadStarted]
   │                        │  warmManager.claim() ?: 
   │                        │  GeminiSessionFactory.create(key, config)      [KeyLoaded, SettingsReady, SocketCreated]
   │                        │     └─ OkHttpGeminiLiveSession(newWebSocket) ─► HTTP 101 ─► onOpen ── sends {"setup":...}   [SocketOpen]
   │                        │     └─ events().collect ─► onLiveEvent(holder)
   │                        ▼
   │                     DictationHost.startCapture(metrics)                [CaptureStartRequested, CaptureStarted]
   │                        └─ AudioCapture (RECORD_AUDIO + FGS microphone)
   │                        ▼
   │                     publish(Listening(connecting = !ready))
   │
   │   awaitReadyAndStart: session.awaitReady() ──► ◄── {"setupComplete":{}}      [SetupComplete → Ready]
   │        └─ startActivity() ──► {"realtimeInput":{"activityStart":{}}}         [ActivityStartQueued]
   │        └─ ready.complete()
   │
   │   streamAudio (one ordered sender):
   │        └─ sendAudio(chunk) ──► {"realtimeInput":{"audio":{"data":"<base64>","mimeType":"audio/pcm;rate=16000"}}}
   │             (cold: PreReadyAudioBuffer 150 frames ≈ 3 s → drain in order → overflow = gemini_connection_too_slow)
   │                                                                            [FirstAudioQueued]
   │                                    ◄── binary frame (opcode 0x2) ── {"serverContent":{"inputTranscription":{"text":"<text>"},...}}
   │                                       parseServerMessage → ServerContent → GeminiEvent.TranscriptCandidates
   │                                       └─ TranscriptAccumulator.accept(raw) ── onTranscriptUpdate (reset settle debounce)
   │                                       {"serverContent":{...,"turnComplete":true}} → TurnComplete (retained)  [TurnComplete]
   │
   STOP / auto-stop ──► coordinator.stop() ──► Finalizing                     [Stop]
   │   startFinalization:
   │      deadlineJob (3 s hard deadline)            ──► TranscriptSettled at fire [TranscriptSettled]
   │      capture.requestStop → awaitQuiescence → audioJob.join                 [CaptureQuiesced, LastAudioQueued]
   │      endActivity() ──► {"realtimeInput":{"activityEnd":{}}}                [ActivityEndQueued]
   │      afterActivityEnd → settle debounce (250 ms) → settle()
   │          └─ accumulator.settledText → ResultCandidate → TranscriptSelector.select
   │               (None → lenientAccept → insert | failNoTranscript)
   │          └─ insertSettled → DictationHost.sendInsertion(sessionId, text)   [InsertionRequested → Inserting]
   │               └─ DictionaryCorrections.apply(text, cachedDictionary)
   │               └─ RuntimeIpc.MSG_INSERT (Messenger) ──► accessibility process ──► commitText(target)
   │          ◄── RuntimeIpc.MSG_INSERT_RESULT ── onInsertionResult(sessionId, result) [InsertionResult]
   │               └─ Success | Error(insert_ambiguous / failed) → Idle after returnToIdleMs
   │          teardown → session.close() ──► WebSocket close(1000, "session closed")
   │
   ▼
 DictationHost.onSessionFinished(state, metrics, transcript)
     └─ "SESSION DONE outcome=... <metrics.summary()>"
     └─ historyRepository.record(...)  (opt-in, settled transcript only)
```

Seams: `GeminiLiveSession` (contract), `DictationHost` (Android-facing host
interface), `AudioPipeline` (capture abstraction), `TranscriptAccumulator` /
`TranscriptSelector` (pure settlement), `MutableSessionMetrics` (pure
diagnostics), `WarmLiveSessionManager` (warm pool), `RuntimeIpc` (typed
cross-process Messenger contract: `MSG_REGISTER_REPLY=1`, `MSG_ELIGIBILITY=2`,
`MSG_INSERT=3`, `MSG_INSERT_RESULT=4`, keys `session_id` / `insert_text`).

---

## How this was learned

This reference was written by reading the code, not the marketing docs. Every
claim above traces to a specific line or kdoc in the modules listed in §"Key
source files" below. The high-level behavior, device-verified results, and the
release history (A through 0.3.1) live in `docs/GEMINI_LIVE_TRANSCRIPTION.md`;
the 0.4.0 evolution (polish levels, auto-stop, history, dictionary corrections,
the docs wave that produced this file) is specified in
`docs/IMPLEMENTATION_PLAN_3.md`. Field-level facts that were learned
empirically rather than from the API reference — the `languageCode` rejection
(`"unknown name language code"`), binary-frame delivery, the 20 s pong timeout,
and close codes 1000/1008 — are explicitly called out inline.

### Key source files

| File | Role |
| --- | --- |
| `platform/gemini/GeminiLiveWire.kt` | Pure wire codec (build + parse) |
| `platform/gemini/GeminiSessionConfig.kt` | Per-session config defaults |
| `platform/gemini/GeminiSessionFactory.kt` | Endpoint URL, default client, default model |
| `platform/gemini/OkHttpGeminiLiveSession.kt` | OkHttp WebSocket session + transport state machine |
| `platform/gemini/WarmLiveSessionManager.kt` | Warm prewarm pool (backoff + idle) |
| `platform/runtime/DictationCoordinator.kt` | Orchestration, settlement, auto-stop, failures |
| `platform/runtime/FlowRuntimeService.kt` | Android host (key/settings, capture, IPC, logging) |
| `core/model/LanguageMode.kt`, `core/model/TranscriptionStyle.kt` | Instruction mapping |
| `core/model/MutableSessionMetrics.kt` | Monotonic diagnostics + `summary()` |
| `core/transcript/TranscriptAccumulator.kt`, `core/transcript/TranscriptSelector.kt` | Settlement |
| `core/model/DictationState.kt`, `core/model/DictationFailure.kt`, `core/model/SendResult.kt` | Typed state/failure contracts |
| `core/audio/GemAudioFormat.kt`, `core/model/AudioChunk.kt` | Audio framing (640 B / 20 ms @ 16 kHz) |
| `audio/PreReadyAudioBuffer.kt` | Bounded pre-ready buffer |
| `platform/ipc/RuntimeIpc.kt` | Typed cross-process message contract |
