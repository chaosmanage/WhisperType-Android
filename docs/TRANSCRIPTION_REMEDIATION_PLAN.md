# Gemini Live Transcription Reliability and Latency Remediation Plan

**Date:** 2026-08-05  
**Source investigation:** `docs/FAILURE_TRANSCRIPTION.md`  
**Current implementation:** `0.2.11` (`versionCode 13`)  
**Status:** Approved implementation plan; no remediation phase is considered verified until its acceptance gate passes on the target device.

---

## 1. Purpose

This plan addresses two related but independently measurable failures in the current Gemini Live dictation path:

1. The Live server rarely returns the user's speech through `inputTranscription`, so dictation usually produces no usable text.
2. Tap-to-listening and stop-to-insertion latency are too high, including finalization stalls of eight to ten seconds.

The work is deliberately split into phased releases. The first release corrects the Live wire protocol and measures whether that correction restores transcription. Later releases fix local races and performance bottlenecks, then optionally add connection prewarming and pre-ready audio capture.

This separation is required because a faster client cannot compensate for a Live service that does not return input transcription, and protocol experiments are not trustworthy while stale jobs, dropped trailing audio, or overlapping sessions can affect the result.

---

## 2. What is known

### 2.1 Confirmed client defects

The following defects are present in the current source and do not depend on interpretation of the device logs.

#### A. The app closes the wrong input stream

Microphone audio is sent as realtime input:

```json
{
  "realtimeInput": {
    "audio": {
      "data": "<base64 PCM16>",
      "mimeType": "audio/pcm;rate=16000"
    }
  }
}
```

STOP currently sends:

```json
{"clientContent":{"turnComplete":true}}
```

`clientContent.turnComplete` tells the server to generate from accumulated `clientContent`. It is not the explicit activity boundary for audio sent through `realtimeInput`.

The v1beta Live protocol provides two supported realtime completion modes:

- With automatic activity detection enabled, send `realtimeInput.audioStreamEnd` when the microphone stream ends.
- With automatic activity detection disabled, delimit the push-to-talk utterance with `realtimeInput.activityStart` and `realtimeInput.activityEnd`.

WhisperType is explicitly push-to-talk, so manual activity signaling is the preferred production design.

#### B. The app interleaves an unfinished text turn with realtime audio

Before opening the microphone, the app sends a `clientContent` prime with no `turnComplete`. It then sends realtime audio and eventually sends an empty `clientContent.turnComplete`.

Google's SDK documentation warns that interleaving `send_client_content` and `send_realtime_input` in the same conversation can lead to unexpected results. This is consistent with the observed greetings and acknowledgments: the model can react to the prime independently of the microphone stream.

#### C. The fallback uses the model's speech rather than the user's speech

`outputAudioTranscription` transcribes generated model audio. It does not transcribe microphone input. Waiting for or selecting that stream adds model-generation latency and risks inserting greetings, acknowledgments, or other assistant output.

The dictation source must be `inputTranscription` unless a separately designed and validated fallback is introduced.

#### D. Streaming transcript messages are treated as independent final candidates

Each `inputTranscription` message is appended to `liveCandidates`. `TranscriptSelector` chooses the first valid raw candidate. If the server sends progressive revisions such as `"schedule"`, then `"schedule the meeting"`, the current implementation can insert only `"schedule"`.

#### E. Finalization timers can add up to almost ten seconds

The same `pendingSelection` job is used for the eight-second finalization watchdog and the two-second trailing-transcript grace period. A late `turnComplete` cancels the watchdog and starts a fresh grace period, extending the total wait.

If a transcript arrives just after `turnComplete`, the app still waits for the complete two-second grace period instead of selecting promptly.

#### F. Critical-path work runs on the Android main thread

The runtime scope uses `Dispatchers.Main.immediate`. The tap and streaming paths currently perform or initiate the following work from that scope:

- API-key file access, Android Keystore access, and AES decryption.
- Two sequential DataStore `first()` reads.
- `AudioRecord` construction and `startRecording()`.
- Base64 encoding and JSON construction for 50 audio frames per second.
- PCM RMS scanning, logging, state publication, and WebSocket queueing.

This competes with overlay rendering, STOP delivery, transcript processing, finalization timers, and insertion IPC.

#### G. Every tap creates a new `OkHttpClient`

`GeminiSessionFactory.create()` uses `defaultClient()` as a default argument, constructing a new client for every dictation. This prevents useful reuse of the dispatcher, DNS state, connection pool, and TLS session state and creates unnecessary resources.

#### H. Session resources are not isolated

Session, capture, jobs, candidates, counters, and timers are service-wide mutable fields. An older session's event, timeout, insertion result, or `finally` block can act on or clear a newer session. Duplicate START intents are also not rejected at the runtime boundary.

#### I. Audio shutdown races the producer

STOP calls `Chunker.remaining()` outside the producer while the producer can still be in `Chunker.push()`. It then cancels capture before a guaranteed orderly drain. This can lose, duplicate, or corrupt trailing audio.

### 2.2 Confirmed external facts

- Gemini server messages arrive as binary WebSocket frames for the tested endpoint. The binary callback fix is required and must remain.
- Setup and microphone delivery work.
- REST `generateContent` transcribes the same audio correctly.
- `inputTranscription` has succeeded exactly at least once, proving the field and insertion path can work.
- The Live API/model is a preview service, and input-transcription reliability cannot be guaranteed by client code.

### 2.3 What is not yet proven

It is not yet proven that the wrong activity boundary is the sole reason `inputTranscription` is intermittent. Correcting the boundary is necessary, but the Live model may still have a server-side transcription defect.

It is also not yet proven whether manual activity signaling or automatic VAD plus `audioStreamEnd` performs better for `gemini-3.1-flash-live-preview`. The implementation will therefore run a controlled protocol matrix before making a final production choice.

---

## 3. Success criteria

### 3.1 Correctness gates

The remediation is not complete until the following are demonstrated on the Samsung target device:

- No greeting, acknowledgment, instruction echo, or model preamble is inserted in any acceptance run.
- No transcript from an old session is inserted into a newer session.
- No insertion occurs more than once for one session.
- Audio wire order is always setup, setup complete, activity start, ordered audio, activity end.
- At least 95% of valid test utterances produce a usable input transcript before the hard finalization deadline.
- At least 95% of English acceptance utterances insert exactly or with explicitly accepted punctuation normalization.
- Latin-script Hinglish meets the product-agreed exactness threshold and never changes to Devanagari.
- No finalization waits longer than the configured hard deadline.

### 3.2 Initial latency budgets

These are engineering targets to validate, not claims about unmeasured server behavior.

| Stage | Warm p50 target | Warm p95 target | Cold hard limit |
| --- | ---: | ---: | ---: |
| Tap to visible recording feedback | <100 ms | <150 ms | 250 ms |
| Tap to microphone capture | <150 ms | <300 ms | 500 ms |
| Tap to Live setup complete | <150 ms when prewarmed | <300 ms when prewarmed | 4 s |
| First audio to first input transcript | <1.0 s | <2.0 s | 4 s |
| STOP to activity-end queued | <150 ms | <300 ms | 750 ms |
| STOP to insertion | <800 ms | <1.5 s | 3 s |
| Insertion request to insertion result | <150 ms | <300 ms | 750 ms |

No percentile target should be relaxed until per-stage metrics identify an external limit.

---

## 4. Required implementation order

Execute the work in the following releases. Do not combine all phases into one unmeasurable change.

1. Release A: instrumentation and protocol experiment.
2. Release B: production protocol correction.
3. Release C: session isolation and audio-finalization correctness.
4. Release D: local latency reduction.
5. Release E: transcript settlement and finalization latency.
6. Release F: optional prewarming and immediate capture.

Each release must bump `versionCode` and `versionName`, update `docs/PUSH_TO_DEVICE.md`, pass JVM tests and lint, and complete its stated device gate before the next release begins.

---

## 5. Release A: measurement and protocol experiment

### Goal

Create trustworthy per-stage measurements and determine which documented realtime completion mode produces reliable input transcription on the target model.

### Step A1: add monotonic session timing

Use `SystemClock.elapsedRealtimeNanos()` or `SystemClock.elapsedRealtime()`. Do not use wall-clock timestamps to calculate durations.

Extend `DictationMetrics` or add a session-local timing structure with these events:

| Event | Recording point |
| --- | --- |
| `tapAt` | START intent accepted |
| `keyLoadStartedAt` | Before secret retrieval |
| `keyLoadedAt` | Secret retrieval completed |
| `settingsReadyAt` | Runtime settings snapshot available |
| `socketCreatedAt` | Session/WebSocket construction begins |
| `socketOpenAt` | OkHttp `onOpen` |
| `setupCompleteAt` | Server `setupComplete` parsed |
| `captureStartRequestedAt` | Before `AudioRecord` initialization |
| `captureStartedAt` | `RECORDSTATE_RECORDING` confirmed |
| `activityStartQueuedAt` | Start boundary accepted by WebSocket |
| `firstAudioQueuedAt` | First PCM message accepted by WebSocket |
| `firstInputTranscriptAt` | First non-empty input transcript received |
| `stopAt` | STOP intent accepted |
| `captureQuiescedAt` | Producer has stopped and flushed |
| `lastAudioQueuedAt` | Sender drained the final PCM frame |
| `activityEndQueuedAt` | End boundary accepted by WebSocket |
| `turnCompleteAt` | Server turn completion parsed |
| `transcriptSettledAt` | Final input candidate selected |
| `insertionRequestedAt` | IPC insert sent |
| `insertionResultAt` | IPC insert result received |

Log only derived durations, event flags, and counts. Never log transcript text, audio, full server frames, API keys, or authenticated URLs.

### Step A2: add protocol counters

Record the following aggregate values per session:

- Captured frame count.
- Audio frames accepted by WebSocket.
- Rejected frame count.
- Maximum WebSocket queue size at sampled checkpoints.
- Input-transcription message count.
- Output-transcription message count.
- Whether `turnComplete` arrived.
- Whether selection used the hard deadline.
- Whether audio-buffer overflow occurred.

### Step A3: add experimental wire builders

In `GeminiLiveWire.kt`, add pure builders for:

```json
{"realtimeInput":{"activityStart":{}}}
```

```json
{"realtimeInput":{"activityEnd":{}}}
```

```json
{"realtimeInput":{"audioStreamEnd":true}}
```

Add setup support for:

```json
{
  "realtimeInputConfig": {
    "automaticActivityDetection": {
      "disabled": true
    }
  }
}
```

Keep experiment selection internal to tests/probes. Do not expose a user setting for an implementation detail.

### Step A4: add exact wire tests

Update `GeminiLiveWireTest.kt` to verify:

- Manual activity setup uses the exact camel-case field names expected by v1beta JSON.
- Activity start and activity end are realtime-input messages.
- Audio stream end is a realtime-input message with Boolean `true`.
- Audio format remains `audio/pcm;rate=16000`.
- Existing binary server-message parsing remains covered.

Update `OkHttpGeminiLiveSessionTest.kt` or add a dedicated probe test to record complete client-message order.

### Step A5: run the controlled host matrix

Use one known intelligible PCM file for all variants. Run at least ten sessions per variant.

| Variant | Text prime | Activity detection | Completion signal |
| --- | --- | --- | --- |
| Existing control | Existing prime | Automatic/default | `clientContent.turnComplete` |
| Automatic realtime | None | Automatic/default | `realtimeInput.audioStreamEnd:true` |
| Manual realtime | None | Disabled | `activityStart`, audio, `activityEnd` |

For every run record:

- Setup success.
- Input-transcription presence.
- Exact transcript match.
- Turn-complete presence.
- First-audio-to-first-transcript latency.
- Completion-signal-to-turn-complete latency.
- Any model greeting or acknowledgment.

### Step A6: run the same matrix on-device

Repeat each non-baseline variant at least five times on the Samsung target with fresh short English utterances. Host-file success alone is not sufficient because the Android microphone, pacing, network route, and device scheduling are part of the production path.

### Release A gate

Choose manual activity for Release B if it satisfies all of these:

- Setup succeeds in every valid run.
- Activity start, audio, and activity end are accepted in order.
- Turn completion arrives in at least 95% of runs.
- Input transcription is materially more reliable than the existing control.
- No greeting or acknowledgment is returned as a candidate.

If manual activity is rejected or materially worse, choose automatic VAD plus `audioStreamEnd`. Never return to `clientContent.turnComplete` as the realtime microphone completion signal.

If both documented realtime variants reliably complete the turn but input transcription remains intermittent, record that result immediately. It means the app-side lifecycle defect is corrected but the selected Live model still does not meet the transcription requirement.

---

## 6. Release B: production protocol correction

### Goal

Replace the mixed client-content/realtime flow with one documented realtime activity flow and make user input transcription the only dictation source.

### Step B1: remove the dictation prime

Delete the call to `session.sendTextTurn(DICTATION_PRIME_TEXT)` from `FlowRuntimeService.kt`.

Remove:

- `GeminiLiveSession.sendTextTurn`.
- `OkHttpGeminiLiveSession.sendTextTurn`.
- `GeminiLiveWire.buildTextTurn`.
- `FlowRuntimeService.DICTATION_PRIME_TEXT`.
- Tests and comments that describe model echo as dictation.

The normal dictation path must send no `clientContent` message.

### Step B2: remove model-output transcription from candidate selection

Set `outputAudioTranscription` to `false` by default, or remove the option if no concrete caller needs it.

Remove `sawInputTranscription` and the output-transcription echo gate from `OkHttpGeminiLiveSession`.

Do not emit a user dictation candidate from `outputTranscription`.

Continue parsing unknown or output fields if useful for protocol compatibility, but do not select them and do not log their text.

### Step B3: configure the selected activity mode

For the preferred manual design, setup must include:

```json
{
  "setup": {
    "model": "models/<configured-model>",
    "generationConfig": {
      "responseModalities": ["AUDIO"]
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "disabled": true
      }
    },
    "inputAudioTranscription": {}
  }
}
```

Do not include a system instruction, text prime, or output-audio transcription in the initial corrected configuration.

### Step B4: update the session contract

Change `GeminiLiveSession` to expose explicit activity operations:

```kotlin
suspend fun startActivity(): SendResult
suspend fun sendAudio(chunk: AudioChunk): SendResult
suspend fun endActivity(): SendResult
```

Enforce these states inside `OkHttpGeminiLiveSession`:

```text
Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
```

Required behavior:

- Start before ready is rejected.
- Duplicate start sends no second wire message.
- Audio before start is rejected.
- Audio after end is rejected.
- Duplicate end sends no second wire message.
- A failed `WebSocket.send` returns rejection immediately.
- Close remains idempotent.

Use atomic state or one serialized outbound owner so callbacks and caller coroutines cannot violate the state machine.

### Step B5: enforce outbound order

The runtime sequence must be:

```text
await setupComplete
startActivity accepted
start microphone or release buffered microphone frames
send ordered PCM frames
stop and flush capture
drain ordered sender
endActivity accepted
```

If Release A selects automatic VAD, omit activity start and replace activity end with `audioStreamEnd:true`, preserving all audio-before-end ordering.

### Step B6: fail promptly when boundaries are rejected

If start or end cannot be queued, emit a typed transport failure. Do not enter an eight-second wait for a completion message that cannot arrive.

### Step B7: update tests

Add MockWebServer tests for:

- Exact setup fields.
- Setup as the first client message.
- Exactly one activity start.
- Audio rejection before activity start.
- Ordered audio messages.
- Exactly one activity end.
- Audio rejection after activity end.
- Immediate propagation of a rejected end boundary.
- No `clientContent` message in a dictation session.
- Output transcription never emits a user candidate.

### Release B gate

Run ten English and ten Latin-script Hinglish sessions on-device. Release B passes only if:

- No greeting or acknowledgment is inserted.
- No session relies on the old `clientContent.turnComplete` message.
- Turn completion or a valid settled input transcript arrives within the hard deadline in at least 95% of sessions.
- Input-transcription success is high enough to continue using the Live-only constraint.

---

## 7. Release C: session and audio correctness

### Goal

Eliminate local races, stale callbacks, and trailing-audio loss before optimizing connection startup.

### Step C1: add a per-session holder

Replace service-wide session fields with one holder similar to:

```kotlin
private data class ActiveLiveSession(
    val sessionId: SessionId,
    val session: GeminiLiveSession,
    val capture: AudioCapture,
    val candidates: TranscriptAccumulator,
    val metrics: MutableSessionMetrics,
    var sessionJob: Job? = null,
    var eventJob: Job? = null,
    var audioJob: Job? = null,
    var amplitudeJob: Job? = null,
    var captureFailureJob: Job? = null,
    var finalizationJob: Job? = null,
    var transcriptSettleJob: Job? = null,
)
```

Use the smallest shape that provides one ownership boundary. The service should keep one `activeSession` reference instead of parallel mutable fields.

### Step C2: reject duplicate START synchronously

Accept START only from `DictationState.Idle`. Move to `Starting` before launching any suspending operation.

Ignore a second START emitted before overlay recomposition.

### Step C3: validate session identity everywhere

Before handling an event or delayed callback, verify:

- `activeSession` is the same holder.
- The state contains the same `sessionId`.

Apply this to:

- Ready transitions.
- Transcript events.
- Turn completion.
- Session failure/end.
- Finalization timeout.
- Transcript-settle timeout.
- Insertion result.
- Delayed return to idle.
- Cleanup in `finally`.

### Step C4: compare-and-clear during cleanup

An old session's cleanup must not null or cancel a new session's resources. Clear `activeSession` only if it still points to the holder being cleaned.

### Step C5: correlate insertion results

Include `RuntimeIpc.KEY_SESSION_ID` in every `MSG_INSERT_RESULT` response.

Change the runtime callback to receive both session ID and result. Ignore a response that does not match the currently inserting session.

Guard delayed terminal-state reset by the same session ID.

### Step C6: give the audio producer exclusive `Chunker` ownership

Remove external `forceRemainingChunk()` use. The producer must be the only coroutine that calls `Chunker.push()` and `Chunker.remaining()`.

Implement orderly shutdown:

1. Mark stop requested.
2. Stop/release `AudioRecord` to unblock the read.
3. Let the producer process bytes already returned.
4. Emit all complete frames.
5. Emit the zero-padded partial frame from `remaining()`.
6. Close the chunk channel.
7. Join the ordered sender.
8. Send the realtime completion boundary.

Use a bounded orderly-shutdown timeout. Hard cancellation is a fallback after the orderly path times out, not the normal stop mechanism.

### Step C7: consume capture failures

Collect `AudioCapture.failures` for the active session. A read failure must close the capture channel, fail the matching session, and tear down the socket. It must not leave the UI listening indefinitely.

### Step C8: test orchestration races

Extract only enough orchestration into a host-testable coordinator if the Android service cannot be tested directly. Cover:

- Duplicate START.
- Cancel during setup.
- STOP immediately after listening starts.
- Old transcript after a new session starts.
- Old failure after a new session starts.
- Old cleanup after a new session starts.
- Late insertion response.
- Delayed reset during a new session.
- Capture read failure.
- STOP during a blocking read.
- Final partial-frame ordering.

### Release C gate

Run ten rapid back-to-back sessions, including cancellation and immediate restart. No stale event may alter a newer session, and frame counters must prove that every orderly STOP queues all captured frames before the end boundary.

---

## 8. Release D: local latency reduction

### Goal

Remove avoidable work from tap-to-capture and prevent 50 Hz audio processing from starving UI and finalization work.

### Step D1: move secret work off main

Run file, Android Keystore, and cipher operations on `Dispatchers.IO`.

Remove the two-step tap path that calls `hasKey()` and then `provideKey()`. Retrieve/decrypt once:

- Non-null key: continue Live setup.
- Null key: show the configured no-key behavior.

Maintain a non-secret cached configured-state value for eligibility. Do not keep plaintext API-key material in a long-lived cache solely for performance without a separate security decision.

### Step D2: cache one runtime settings snapshot

Expose one immutable snapshot containing:

- Speech mode.
- Model override.

Collect DataStore eagerly at service/application scope and read the latest in-memory value on tap. Do not perform two sequential `first()` calls in the critical path.

### Step D3: reuse one `OkHttpClient`

Construct one service/application-scoped client and pass it to every `GeminiSessionFactory.create()` call.

Do not shut down its dispatcher or connection pool when an individual WebSocket closes.

Keep:

- No WebSocket read timeout.
- A suitable ping interval.
- Explicit connection/setup timeouts at the session layer.

### Step D4: move microphone initialization off main

Create and start `AudioRecord` on a dedicated audio or IO dispatcher. Catch `startRecording()` exceptions and return a typed microphone-init failure.

Publish `Listening` only after recording state is confirmed, unless Release F introduces an explicit recording-while-connecting UI state.

### Step D5: move audio encoding and sending off main

Use one ordered sender coroutine on a serialized non-main dispatcher. It should perform:

- Base64 encoding.
- JSON building.
- WebSocket queueing.
- Send accounting.

Do not launch one coroutine per audio frame.

### Step D6: compute RMS once

Calculate aggregate amplitude in the capture path. Remove the second full PCM scan from `FlowRuntimeService`.

### Step D7: throttle UI amplitude

Conflate or sample waveform updates to 10-20 Hz. Audio capture and transmission remain at their required cadence; only UI state publication is throttled.

### Step D8: reduce callback logging

Remove raw server-message and transcript-content logging. Keep aggregate debug-only stage metrics. Avoid per-frame logs in release behavior.

### Step D9: start the event collector immediately

Install the session event collector as soon as the session is created, before awaiting readiness. This ensures setup failures and closure are processed promptly while capture/network work proceeds.

### Release D gate

Compare at least ten cold and ten warm process runs against the Release A baseline. Report p50, p95, and maximum for every measured stage. Confirm via StrictMode or instrumentation that key/file work no longer runs on main.

---

## 9. Release E: transcript settlement and fast finalization

### Goal

Produce one complete transcript and insert promptly without overlapping grace periods or waiting for unnecessary model output.

### Step E1: implement a session-local transcript accumulator

Track one current input transcript, not an append-only candidate list.

Before finalizing merge behavior, measure whether the server sends:

- Independent deltas.
- Cumulative revisions.
- Corrected revisions.
- A mixture of those forms.

Safe diagnostics may record lengths, overlap lengths, and prefix relationships, but not transcript content.

### Step E2: implement tested merge rules

The accumulator must handle:

- Empty message: ignore.
- Exact duplicate: ignore.
- New value extends current value: replace with the longer cumulative value.
- New value corrects a prior provisional value: replace according to measured server semantics.
- Confirmed delta: append without destroying meaningful whitespace.

Do not trim every fragment before merge because fragment spaces can carry word boundaries.

### Step E3: validate only the settled candidate

At settlement, create one `ResultCandidate` from accumulated input transcription, run `TranscriptSelector`, and insert once.

Do not make the earliest partial transcript the preferred final candidate.

### Step E4: replace additive timers with one absolute deadline

Set one monotonic deadline when STOP is accepted. Initial hard limit:

```text
3 seconds from STOP to selection or explicit failure
```

Use separate jobs for the absolute deadline and transcript-settle debounce, but cap all work by the same absolute deadline.

### Step E5: settle promptly after transcript updates

After activity end:

- If a valid input transcript exists and `turnComplete` arrives, start a short settle debounce.
- If a transcript arrives after `turnComplete`, cancel the empty-transcript wait and start/reset the short debounce immediately.
- Each new transcript revision resets the debounce, but never extends the absolute deadline.

Start with a 250 ms debounce and calibrate it from measured trailing-message timing.

### Step E6: define hard-deadline behavior

At the deadline:

- Valid settled input transcript: insert it.
- No valid transcript: fail with `gemini_no_transcript`.
- Clearly provisional/truncated transcript: fail rather than silently inserting incomplete text.
- Close the session in all cases.

No additional grace period may begin after the hard deadline.

### Step E7: retain early turn-complete state

Do not discard a `turnComplete` received before STOP. Associate it with the active session/activity and use it during finalization.

### Step E8: add virtual-time tests

Cover:

- Transcript before turn completion.
- Transcript immediately after turn completion.
- Multiple revisions during debounce.
- Turn completion just before the deadline.
- Transcript just before the deadline.
- No transcript.
- Deadline cannot be extended by grace.
- Exactly one selection and insertion.

### Release E gate

No on-device session may remain in finalizing beyond three seconds. For sessions that already have a valid transcript at STOP, p95 stop-to-insert should be below 1.5 seconds.

---

## 10. Release F: optional prewarming and immediate capture

### Goal

Remove the unavoidable cold WebSocket setup from the perceived tap-to-record path.

This release is optional until Releases B-E prove that Live input transcription is reliable enough to retain.

### Step F1: verify billing and lifecycle constraints

Before production prewarming, confirm for the current Gemini account and model:

- Whether a setup-complete but idle session incurs billable usage.
- Maximum session lifetime.
- Idle timeout behavior.
- Concurrent session limits.
- Whether reconnects consume material quota.
- Whether session resumption helps setup latency for this short-lived push-to-talk use case.

Do not assume idle sessions are free.

### Step F2: define warm-session eligibility

Prewarm only while all conditions are true:

- App enabled.
- Accessibility connected.
- Eligible non-secure editor focused.
- Keyboard visible.
- Microphone permission granted.
- API key configured.
- No active dictation.

Close the warm session when eligibility is lost beyond the existing focus/keyboard grace period, the screen turns off, settings change, network fails, or the warm idle timeout expires.

### Step F3: implement a warm-session state machine

Use explicit states:

```text
None -> Connecting -> Ready -> Claimed -> Closing
                     |                    |
                     +------ failure -----+-> Backoff
```

Claim a ready session atomically on tap. Never permit two active dictations to share one warm session.

Apply bounded reconnect backoff, for example 1, 2, 5, then 10 seconds maximum.

### Step F4: start capture immediately on tap

On an accepted START:

1. Publish immediate recording feedback.
2. Promote the foreground service to microphone type.
3. Start `AudioRecord` on the audio dispatcher.
4. Claim a ready session or continue a cold connection concurrently.
5. If ready, send activity start and begin ordered streaming.
6. If not ready, place PCM into a bounded in-memory pre-ready buffer.

The UI should distinguish recording from network readiness, for example `Listening` with a secondary `Connecting` status.

### Step F5: bound the pre-ready buffer

Initial limit:

- Three seconds.
- 150 frames at 20 ms.
- Approximately 96 KB PCM16 at 16 kHz mono.

Requirements:

- Memory only.
- Session scoped.
- Cleared on cancel/failure.
- No silent drop-oldest behavior.
- Overflow produces an explicit connection-too-slow failure.

### Step F6: validate buffered-audio catch-up

Do not send several seconds of buffered audio as an unbounded burst without testing.

Run accuracy/latency experiments at:

- Realtime pacing.
- 1.25x realtime.
- 1.5x realtime.
- 2x realtime.

Choose the fastest rate that preserves transcript accuracy and turn behavior. Continue to preserve strict frame order while new microphone frames arrive.

### Step F7: use a conservative warm idle timeout

Start with 30 seconds after eligibility or use ends. Adjust only after billing and connection metrics are available.

### Release F gate

Run ten warm and ten forced-cold sessions. Warm tap-to-capture p95 must be below 300 ms, and pre-ready buffering must not reduce exact transcription accuracy or lose the beginning of an utterance.

---

## 11. Accessibility insertion timing

Insertion took approximately 83 ms in the one documented successful session, so it is not the first optimization target. It still requires identity and timing fixes.

Add aggregate timestamps for:

- IPC request received.
- Target validation complete.
- `commitText` started and returned.
- Verification complete.
- IPC result sent and received.

Keep accessibility and `InputConnection` calls on their required thread unless instrumentation proves a bottleneck and Android behavior is validated on all supported SDK levels.

---

## 12. Verification commands

Run after each behavior-changing release:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Run connected tests when the target device is available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Inspect the final diff before packaging. Do not commit device logs, audio samples, API keys, authenticated URLs, or generated diagnostic transcripts.

---

## 13. On-device acceptance matrix

Use at least this matrix after Releases B, C, E, and F:

| Scenario | Runs |
| --- | ---: |
| Short English, 1-3 words | 10 |
| Normal English sentence | 10 |
| Long English sentence | 10 |
| Short Latin-script Hinglish | 10 |
| Normal Latin-script Hinglish | 10 |
| Speak immediately after tap | 10 |
| STOP immediately after speech | 10 |
| Pause inside an utterance | 10 |
| Cancel during connection | 5 |
| Rapid consecutive sessions | 10 |
| Forced cold process/network path | 10 |
| Warm eligible-session path | 10 |

For every run record only aggregate outcomes:

- Exact insertion.
- Accepted punctuation-only difference.
- Truncated insertion.
- Wrong model text.
- No transcript.
- Timeout.
- Tap-to-capture duration.
- First-audio-to-first-transcript duration.
- Stop-to-insert duration.

---

## 14. Decision tree after protocol correction

### Outcome 1: realtime activity signaling restores reliable input transcription

Continue through Releases C-F. The principal transcription defect was client lifecycle signaling, compounded by local races and latency.

### Outcome 2: turn completion becomes reliable but input transcription remains intermittent

Treat the app-side activity defect as fixed and the remaining transcription defect as a Live model/service limitation. Local timeout or selector changes cannot manufacture a transcript the server did not return.

Choose one product direction:

1. Permit REST audio transcription.
2. Add a documented on-device ASR fallback.
3. Accept explicit retry behavior and its latency/reliability cost.
4. Test a different supported Live model.
5. Report the controlled regression matrix to Google.

Do not reintroduce output-transcription echo as user dictation.

### Outcome 3: manual activity signaling is rejected

Use automatic VAD and send `realtimeInput.audioStreamEnd:true` after orderly capture drain. Repeat the protocol matrix. Do not use `clientContent.turnComplete` for realtime audio.

### Outcome 4: transcription is reliable but server latency remains above budget

Complete Release F. If prewarming, immediate capture, off-main processing, and fast settlement still cannot meet the stop-to-insert budget, document that the selected Live service does not meet the product latency requirement.

---

## 15. Rollback rules

- Keep the existing binary-frame parsing under every outcome.
- Do not roll back to the text prime or model-echo candidate path.
- If manual activity is incompatible with the model, roll forward to automatic VAD plus `audioStreamEnd`, not backward to client-content completion.
- If prewarming affects billing, battery, or connection stability, disable only prewarming; retain protocol, session-isolation, off-main, accumulator, and deadline fixes.
- If accelerated buffered-audio catch-up affects accuracy, use realtime pacing or disable immediate-capture buffering while retaining shared-client and cached-settings improvements.
- If the Live service remains unreliable after both documented completion variants pass wire validation, stop optimizing the Live-only path until the product constraint changes or the upstream service improves.

---

## 16. Expected end state

The intended final architecture is:

- One shared `OkHttpClient`.
- One session-local owner for all jobs, resources, metrics, and transcript state.
- Optional eligibility-driven warm Live session.
- Immediate microphone capture after an accepted tap.
- Small bounded memory-only pre-ready PCM buffer on cold setup.
- One documented realtime input mode with the correct completion boundary.
- No client-content dictation prime.
- No output-transcription fallback.
- Ordered non-main audio encoding and WebSocket queueing.
- Producer-owned final audio flush.
- One accumulated input transcript.
- One absolute three-second finalization deadline.
- Short measured transcript-settlement debounce.
- Session-correlated exactly-once insertion.
- Aggregate monotonic latency diagnostics with no transcript or audio logging.

This architecture will fix the confirmed client protocol misuse, greeting/acknowledgment insertion, local session races, trailing-audio race, main-thread contention, partial-candidate selection, and excessive additive timeouts. It cannot guarantee that a preview Gemini Live model will return `inputTranscription`; that capability must pass the Release A/B device gates before the Live-only design is considered viable.
