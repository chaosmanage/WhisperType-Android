# Gemini Live Voice Transcription Engine

This document describes the **Gemini Live dictation engine** as it exists at
`0.3.1`: how it was built, what is sent and read on the wire, the exact prompt,
how the modules connect, the session/settlement state machines, and the
failsafes. It is the authoritative reference for the current voice-to-text
pipeline; companion docs are `docs/ON_DEVICE_TEST_PROTOCOL.md` (how to verify on
a phone) and `docs/TRANSCRIPTION_REMEDIATION_PLAN.md` (the original plan this
was built from).

> **0.4.0 updates** — for the exhaustive wire minutia of the 0.4.0 wire (setup
> fields, realtime activity boundaries, parse rules), see
> `docs/GEMINI_LIVE_WIRE_REFERENCE.md`. In brief: **Output polish** maps
> None/Low/Medium/High (default Medium) to a `systemInstruction` sent in setup —
> higher levels ask for cleaner filler/disfluency handling; None sends no polish
> instruction. **Auto-stop** adds a silence threshold plus a hard session cap,
> both governed by the Auto-stop timeout setting (15/30/60/120/300 s, default 60).
> The per-session `SESSION DONE` line keeps the shape documented in §11 with the
> optional `reject=<rule>` / `lenient=true` fields.

---

## 1. TL;DR — what the engine is

WhisperType captures 16 kHz mono PCM16 from the microphone, streams it over one
WebSocket to Google's **Gemini Live** API (`BidiGenerateContent`), and reads the
server's transcription of the **user's speech** (`serverContent.inputTranscription`).
The app is **push-to-talk**: it manually delimits each utterance with
`realtimeInput.activityStart` / `realtimeInput.activityEnd` (automatic VAD is
disabled). The only dictation source is `inputTranscription`; the model's own
spoken output (`outputTranscription`) and `modelTurn` text are parsed for
diagnostics but **never** used as dictation.

Device-verified behavior (Samsung S25, 0.3.1): `inputTranscription` arrives in
essentially every session; short openers, long English sentences, and Hinglish
(inserted in **Latin script**) all work.

---

## 2. How it was built (the steps)

The engine was rebuilt from a known-failing state (`0.2.11`) through a
six-release remediation plan plus a 0.3.1 robustness pass.

### 2.1 The failure being solved
The Live server rarely returned the user's speech through `inputTranscription`,
dictation mostly produced no text, and stop-to-insert latency was high (with
8–10 s finalization stalls). The original diagnosis (`docs/FAILURE_TRANSCRIPTION.md`)
isolated client defects: the app closed the wrong input stream
(`clientContent.turnComplete` instead of a realtime activity boundary), it
interleaved an unfinished text prime with realtime audio, it used the model's
own speech (`outputTranscription`) as a fallback, and the client had session
races, main-thread contention, and additive timers.

### 2.2 Release A (0.2.12) — instrumentation + protocol experiment
- `MutableSessionMetrics`: session-local **monotonic** stage timing (tap, key,
  settings, socket open, setup complete, capture, first audio, first transcript,
  stop, quiesce, activity end, turn complete, settled, insertion) plus aggregate
  counters (frames, queue depth, transcription counts, deadline/overflow flags).
  An injectable clock makes it JVM-testable; durations are never wall-clock.
- Experimental realtime wire builders: `activityStart`, `activityEnd`,
  `audioStreamEnd`, and setup support for `realtimeInputConfig`.
- Exact wire tests.

### 2.3 Release B (0.2.13) — production protocol correction
- **Deleted the dictation prime** (`sendTextTurn` / `buildTextTurn` /
  `DICTATION_PRIME_TEXT`): the normal path sends **no `clientContent`**.
- `inputTranscription` became the **only** candidate source; `outputTranscription`
  and `modelTurn` text are never selected.
- Setup now uses **manual activity signaling**: `realtimeInputConfig.
  automaticActivityDetection.disabled = true`, no systemInstruction, no output
  transcription, audio format stays `audio/pcm;rate=16000`.
- New session contract: `startActivity() / sendAudio() / endActivity()` with an
  enforced `Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed`
  state machine (duplicate start/end send no second wire message; audio before
  start / after end is rejected; a failed send rejects immediately).
- The runtime enforces wire order **setup -> setupComplete -> activityStart ->
  ordered audio -> activityEnd** and fails promptly (typed `gemini_transport`)
  instead of waiting 8 s when a boundary is rejected.

### 2.4 Release C (0.2.14) — session + audio correctness
- Extracted a host-testable **`DictationCoordinator`** with a per-session
  `ActiveLiveSession` holder (one ownership boundary for session, capture,
  transcript accumulator, metrics, and every job).
- Duplicate START rejected synchronously; every event/callback validates session
  identity; cleanup is compare-and-clear (an old holder can never clear a newer
  session).
- Insertion results correlated by `KEY_SESSION_ID`; stale responses ignored.
- `AudioCapture` gives the **producer exclusive `Chunker` ownership**: orderly
  shutdown unblocks the blocking read, flushes the zero-padded partial frame,
  and closes the channel before the completion boundary (bounded
  `awaitQuiescence`, hard-stop fallback). Capture read failures fail the session.
- Nine coordinator race tests + audio shutdown tests.

### 2.5 Release D (0.2.15) — local latency
- Secret retrieval (file/Keystore/AES), `AudioRecord` construction/start, and
  base64/JSON/queue work all moved off the main thread.
- Eager in-memory runtime-settings snapshot replaces two sequential DataStore
  `first()` reads.
- One service-scoped `OkHttpClient` shared by every session.
- UI amplitude sampled ~16.7 Hz while capture/transmission stay 50 Hz; raw
  server-frame and transcript-content logging removed.

### 2.6 Release E (0.2.16) — settlement + fast finalization
- `TranscriptAccumulator` merges streaming `inputTranscription` revisions into
  one cumulative value (extensions, duplicates, corrections, word-boundary
  protection); only the settled candidate is validated and inserted.
- One **absolute 3 s monotonic deadline** from STOP; a **250 ms settle debounce**
  resets on each revision but can never extend the deadline.
- Hard-deadline behavior: valid transcript -> insert; none -> `gemini_no_transcript`;
  a clearly provisional fragment -> fail. Early `turnComplete` is retained and
  reused. Seven virtual-time settlement tests.

### 2.7 Release F (0.2.17) — prewarming + immediate capture
- `WarmLiveSessionManager`: eligibility-driven prewarm pool
  (`None/Connecting/Ready/Claimed/Closing/Backoff`), atomic claim, bounded
  backoff (1/2/5/10 s), conservative 30 s warm idle timeout; a claimed session
  is never closed by losing eligibility.
- Capture starts **immediately on the accepted tap**; cold-session PCM flows into
  a bounded 150-frame **pre-ready buffer** and drains in strict order once the
  session is ready (overflow fails with `gemini_connection_too_slow`).
  `DictationState.Listening` gains a `connecting` flag for the UI.

### 2.8 Release 0.3.0 — consolidation + per-session diagnostics
- All A–F work consolidated, versioned 0.3.0, git commit + BuildConfig.GIT_COMMIT
  surfaced on the home screen.

### 2.9 Release 0.3.1 — trust user speech, Hinglish, failsafes
On-device `SESSION DONE` analysis showed every "No transcript could be
recognized" failure had `inputTx=1` + `hardDeadline=false`: a transcript
**arrived** but `TranscriptSelector` rejected it. The selector's preamble /
greeting / acknowledgment / repetition heuristics had been designed for **model
output**, but the source is the user's own ASR speech, which legitimately starts
with "Okay…", "Yes…", "Of course…", "Note that…". Changes:
- **`TranscriptSelector` user-speech trust policy**: only rejects blank,
  punctuation-only, garbled, and Devanagari-in-English (Hinglish accepts
  Devanagari so it never errors). Removed the model-preamble/greeting/ack/
  repetition rejections. Added `diagnose()` for rejection-reason logging.
- **Hinglish reaches the model**: a Hinglish `systemInstruction` biases the Live
  transcription to Latin script. A `languageCode` on `inputAudioTranscription`
  was tried but the Live API **rejects it** ("unknown name language code"), so it
  is not sent.
- **Failsafes**: a lenient fallback inserts a rejected transcript unless truly
  unusable; a **Retry** button on the error panel; retryable errors persist
  until Retry/Dismiss; `SESSION DONE` logs `reject=<rule>` and `lenient=true`.

---

## 3. What actually ended up working (device-verified)

| Concern | Result |
| --- | --- |
| Server returns user speech as text | **Working** — `inputTranscription` arrives in nearly every session (the manual activity boundary + continuous real-time pacing fixed delivery). |
| No greetings / acknowledgments inserted | **Working** — output transcription is never a candidate source. |
| Short sentences with natural openers ("Okay so…", "Yes…") | **Working** — selector trusts user speech (0.3.1). |
| Long English sentences | **Working** — same trust policy; settlement is debounce + 3 s hard deadline. |
| Hinglish (Hindi+English, Latin output) | **Working** — Hinglish `systemInstruction` produces Latin-script romanized Hinglish. |
| Stop-to-insert latency | Fast — typically ~0.5–1.6 s (debounce path), never waiting on `turnComplete` (which the server rarely sends). |
| Retry / failsafe on genuine failures | **Working** — Retry button + persistent retryable errors + lenient fallback. |
| Known server behavior | The preview Live model's `inputTranscription` is still fundamentally intermittent; if it returns nothing within the hard deadline the app reports `gemini_no_transcript` with a Retry affordance. |

> **Not used / rejected on the wire**: `languageCode` inside
> `inputAudioTranscription` is rejected by the Live API. `outputAudioTranscription`
> is off by default. `modelTurn` text is impossible (voice-only model rejects
> TEXT modality). `clientContent.turnComplete` is never used for realtime audio.

---

## 4. The wire protocol

### 4.1 Endpoint
```
wss://generativelanguage.googleapis.com/ws/
  google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
```
Built by `GeminiSessionFactory.buildWsUrl`. The key is a query parameter and is
**redacted in all logs** (`key=<redacted>`).

### 4.2 First client message — `setup` (exact fields)
```json
{
  "setup": {
    "model": "models/gemini-3.1-flash-live-preview",
    "generationConfig": { "responseModalities": ["AUDIO"] },
    "inputAudioTranscription": {},
    "realtimeInputConfig": {
      "automaticActivityDetection": { "disabled": true }
    },
    "systemInstruction": { "parts": [ { "text": "<Hinglish instruction, see §5>" } ] }
  }
}
```
Notes:
- `inputAudioTranscription: {}` — enables `serverContent.inputTranscription`.
  No `languageCode` (the API rejects it).
- `systemInstruction` is present **only for Hinglish**; English sends none.
- `outputAudioTranscription` is **absent** (default false).
- The server replies `setupComplete` (or `setupError`). The app treats
  `setupComplete` as readiness — socket-open alone is not enough.

### 4.3 Client -> server messages
| Message | Shape |
| --- | --- |
| Setup | `{"setup":{...}}` — always the first message |
| Activity start (push-to-talk) | `{"realtimeInput":{"activityStart":{}}}` |
| Audio frame (20 ms) | `{"realtimeInput":{"audio":{"data":"<base64 PCM16>","mimeType":"audio/pcm;rate=16000"}}}` |
| Activity end (push-to-talk) | `{"realtimeInput":{"activityEnd":{}}}` |
| (automatic-VAD variant, unused in production) | `{"realtimeInput":{"audioStreamEnd":true}}` |
| (removed, never used) | `{"clientContent":{"turnComplete":true}}` / text turns |

Wire order for a dictation session is always:
`setup -> setupComplete -> activityStart -> ordered audio frames -> (drain) -> activityEnd`.

### 4.4 Server -> client fields the app reads
Every server message is a **binary WebSocket frame**; the binary callback
decodes to UTF-8 and feeds the parser.

| Field | Parsed into | Used for |
| --- | --- | --- |
| `setupComplete` | `SetupComplete` | Readiness gate (`awaitReady`) |
| `setupError.error.message` | `SetupError` | Typed `gemini_setup` failure |
| `error.message` | `SetupError` | Top-level error -> `gemini_setup` |
| `serverContent.inputTranscription.text` | `inputTranscription` | **The dictation source** — emits a candidate |
| `serverContent.outputTranscription.text` | `outputTranscription` | Counted for diagnostics; **never a candidate** |
| `serverContent.modelTurn.parts[].text` | `textParts` | Parsed for compatibility; **never a candidate** |
| `serverContent.turnComplete` | `turnComplete` | Retained; counted; not required for settlement |
| `serverContent.interrupted` | `interrupted` | Lifecycle flag (unused) |
| `goAway` | `GoAway` | `SessionEnd` |

`inputTranscription` messages arrive as **one cumulative revision per emission**;
the `TranscriptAccumulator` (see §8) merges them into the final value.

---

## 5. The prompt

The only prompt sent is the **Hinglish systemInstruction** (`LanguageMode.liveInstruction()`);
English mode sends none:

> "Transcribe the user's speech exactly as spoken. Write Hindi words in Latin
> script (romanized Hindi / Hinglish), never in Devanagari script. Keep English
> words and phrases exactly as spoken. Output only the transcription, nothing
> else."

This biases the Live transcription to produce **Latin-script romanized Hinglish**
for mixed Hindi+English speech. There is deliberately **no dictation text prime**
and no user-facing prompt field; a systemInstruction is only re-added for
Hinglish because the failed `languageCode` approach cannot bias the script.

---

## 6. Audio pipeline

```
AudioRecord (16 kHz mono PCM16, blocking read, real-time pace)
  -> Chunker (20 ms frames, 640 bytes; producer-owned; zero-pads final partial)
  -> AudioCapture.chunks (bounded ReceiveChannel, cap 64)
  -> DictationCoordinator.streamAudio (one ordered sender coroutine)
       |-- if session ready:  sendAudio(chunk) directly (base64+JSON+queue on Dispatchers.IO)
       `-- if still connecting: PreReadyAudioBuffer (150 frames ≈ 3 s) then drain in order
  -> OkHttpGeminiLiveSession.sendAudio -> WebSocket
```

- **Orderly shutdown** (STOP): `requestStop()` unblocks the blocking read; the
  producer flushes all complete frames plus the zero-padded partial frame, then
  closes the channel; the sender drains and joins before `activityEnd` is sent.
- **Amplitude**: computed once in the capture path from a frame slice; UI
  publication is throttled to ~16.7 Hz while audio stays at 50 Hz.
- **Pre-ready buffering** (cold connect): bounded memory-only buffer; overflow
  yields a typed `gemini_connection_too_slow` failure (no silent drops).

---

## 7. Module connections

```
Main process (FlowRuntimeService)                      :accessibility process
+----------------------------------------------+        +-----------------------+
| overlay intents (START/STOP/CANCEL/RETRY/    |        | WhisperTypeAccessibility
|   DISMISS)  ->  DictationCoordinator         |        |   Service
|   DictationHost impl (this service):         |        |   - EditorTracker (focus/keyboard)
|   - resolveSession (key+settings+session)    |        |   - target capture + commitText
|   - startCapture (permission+FGS+AudioRecord)|        |   - eligibility -> IPC
|   - sendInsertion (IPC)                      |        +-----------------------+
|   - publish(state) / onSessionFinished(...)  |                    ^
|                                              |        Messenger IPC (RuntimeIpc):
+----------------------------------------------+        MSG_INSERT / MSG_INSERT_RESULT
        |  resolveSession returns
        v
DictationCoordinator (pure orchestration, JVM-testable)
  - ActiveLiveSession holder: session, capture, accumulator, metrics, jobs
  - lifecycle: start -> runSession -> Listening -> stop -> Finalizing -> settle -> insert
  - finalization: 3 s hard deadline + 250 ms settle debounce
  - TranscriptAccumulator -> TranscriptSelector (trust policy + diagnose)
  - failsafes: lenient fallback insert, retry()/dismiss(), persistent errors
        |  claim()/cold
        v
WarmLiveSessionManager (eligibility-driven prewarm, 30 s idle, 1/2/5/10 s backoff)
        |
        v
OkHttpGeminiLiveSession (implements GeminiLiveSession)
  - state machine: Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
  - startActivity / sendAudio / endActivity / awaitReady / events() / close
        |
        v
GeminiLiveWire (pure codec)   <--binary frames-->   wss://.../BidiGenerateContent
```

Key seams/interfaces:
- `GeminiLiveSession` (contract) — the session the coordinator drives.
- `DictationHost` — the Android-facing services the coordinator needs
  (publish, resolveSession, startCapture, sendInsertion, onSessionFinished).
- `AudioPipeline` — capture abstraction so orchestration is host-testable.
- `TranscriptAccumulator` / `TranscriptSelector` — pure settlement.
- `MutableSessionMetrics` — pure diagnostics.

---

## 8. Transcript settlement

1. Every `inputTranscription` emission is fed to the session-local
   `TranscriptAccumulator`, which keeps **one cumulative value** (merges
   extensions, ignores duplicates, protects word boundaries).
2. On STOP the coordinator starts finalization: **one absolute 3 s monotonic
   deadline** plus a **250 ms settle debounce**. Each new transcript revision
   resets the debounce; the deadline is never extended.
3. At settlement the single accumulated value becomes a `ResultCandidate` and is
   run through `TranscriptSelector`:
   - Rejects only: blank, punctuation-only, garbled (control/replacement chars),
     Devanagari in **English** mode.
   - Accepts normal user speech including natural openers, long sentences,
     repetition, and (in Hinglish mode) Devanagari.
   - `diagnose()` reports the first rejection rule (never the text).
4. If strict selection rejects the value, the **lenient fallback** still inserts
   it unless it is blank, garbled, or a clearly provisional fragment at the hard
   deadline — the user's own speech is preferred over an error.
5. At the hard deadline with no usable value, the session fails with
   `gemini_no_transcript` (retryable) and shows a **Retry** button.

---

## 9. Session state machine (transport)

```
Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
```
- `startActivity` before Ready -> rejected; duplicate start sends no wire message.
- `sendAudio` before start or after end -> rejected.
- `endActivity` before start -> rejected; duplicate end sends no wire message.
- Failed `WebSocket.send` -> immediate `SendResult.Rejected`.
- `close` is idempotent and reaches Closed from any state.

---

## 10. Warm sessions (Release F)

`WarmLiveSessionManager` preconnects one Live session while eligible (focused
non-secure editor, keyboard visible, mic + key configured, no active dictation):
- States `None/Connecting/Ready/Claimed/Closing/Backoff`.
- `claim()` atomically takes a ready session and starts a replacement prewarm.
- Bounded reconnect backoff 1/2/5/10 s; conservative 30 s warm idle timeout.
- **Billing gate (open)**: idle prewarm sessions may incur billable usage —
  verify before enabling prewarming in production (see `docs/PUSH_TO_DEVICE.md`).

---

## 11. Metrics & diagnostics

Every session logs one line at its terminal state:
```
SESSION DONE outcome=Success tapToCapture=105ms setup=718ms tapToFirstAudio=728ms
firstAudioToFirstTranscript=7861ms stopToQuiesce=65ms stopToActivityEnd=65ms
stopToSettled=553ms stopToInsert=608ms insertToResult=49ms captured=393 accepted=393
rejected=0 maxQueue=1858 inputTx=1 outputTx=0 turnComplete=false hardDeadline=false
overflow=false [reject=DEVANAGARI] [lenient=true]
```
- All durations are **monotonic**; no transcript/audio/keys are ever logged.
- `inputTx` / `outputTx` = input/output transcription message counts.
- `hardDeadline` = settlement came from the 3 s deadline (vs the debounce).
- `reject` = the selector rule that fired (diagnosis); `lenient` = the failsafe
  inserted a rejected transcript.

---

## 12. Key source files

| File | Role |
| --- | --- |
| `platform/gemini/GeminiLiveWire.kt` | Pure wire codec (build + parse) |
| `platform/gemini/GeminiSessionConfig.kt` | Per-session config (model, activity mode, instruction, language) |
| `platform/gemini/GeminiSessionFactory.kt` | Session + shared `OkHttpClient` + endpoint URL |
| `platform/gemini/OkHttpGeminiLiveSession.kt` | OkHttp WebSocket session + transport state machine |
| `platform/gemini/WarmLiveSessionManager.kt` | Warm prewarm pool |
| `platform/runtime/DictationCoordinator.kt` | Pure orchestration + settlement + failsafes |
| `platform/runtime/FlowRuntimeService.kt` | Android host (FGS, IPC, key/settings, capture, logging) |
| `audio/AudioCapture.kt`, `audio/AudioPipeline.kt`, `audio/Chunker.kt` | Capture + orderly shutdown |
| `audio/PreReadyAudioBuffer.kt` | Bounded pre-ready buffer (cold connect) |
| `core/transcript/TranscriptAccumulator.kt` | Cumulative transcript merging |
| `core/transcript/TranscriptSelector.kt` | User-speech trust validation + `diagnose()` |
| `core/model/MutableSessionMetrics.kt` | Monotonic diagnostics + summary |
| `core/model/LanguageMode.kt` | Mode -> systemInstruction mapping |

---

## 13. Verification

- JVM unit tests + lint + assemble: `./gradlew :app:testDebugUnitTest
  :app:lintDebug :app:assembleDebug`.
- On-device protocol + acceptance matrix: `docs/ON_DEVICE_TEST_PROTOCOL.md`.
- Remaining gates (require the phone): the full acceptance matrix, warm/cold
  latency budgets, StrictMode off-main confirmation, and the prewarm billing check.
