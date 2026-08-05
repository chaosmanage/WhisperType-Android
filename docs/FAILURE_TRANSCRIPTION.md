# Failure Report — Gemini Live Dictation Transcription Does Not Reliably Produce Text

**Date:** 2026-08-05 (all-day investigation, ~14:00–23:00)
**Branch:** `rebuild/clean-runtime` (only `c153b9b` and `a168397` are committed; versions 0.2.2–0.2.11 are uncommitted in the working tree)
**Device:** Samsung Galaxy S25 (`SM-S921B`), Android 16 (SDK 36), over adb via Tailscale `100.127.110.79:33395`
**Status:** **FAIL — UNRESOLVED.** The full pipeline is verified working end-to-end except the one thing that matters: the Gemini Live server only rarely returns the user's speech as text. It succeeded exactly once on-device (`inputTranscription`), and every fallback strategy either produces nothing or wrong text (greetings/acknowledgments).

**Remediation plan:** `docs/TRANSCRIPTION_REMEDIATION_PLAN.md` defines the protocol A/B test, production correction, latency work, verification gates, and decision path if Live input transcription remains unreliable.

---

## 1. The goal (Phase 6 / PRD)

`PRD/WisprFlow-Parity-Rebuild-Plan.md` Phase 6 ("Gemini Live Substitution", §359–372) and
`PRD/WhisperType-Android-PRD.md` FR-6: **receive raw and cleaned candidates in one Live
session**, validate them, and pass the selected candidate to the already-proven accessibility
insertion transaction. Samsung gate: one English and one Latin-script Hinglish utterance insert
correctly into SwiftKey fields.

The PRD's intended channel is `inputTranscription` (the user's speech) as the "raw" dictation
source, with the model's rendering as "cleaned". The wire spec is `docs/GEMINI_LIVE_PROTOCOL.md`
(marked partially outdated in `FAILURE_GEMINI_WIRE.md`).

---

## 2. Architecture (as built)

```
Main process (FlowRuntimeService)
  ├─ startLiveDictation(): reads API key + model override (Settings), creates session
  │    ├─ GeminiSessionFactory -> OkHttpGeminiLiveSession (WebSocket to Live endpoint)
  │    ├─ awaitReady()            <- server's setupComplete (NOT socket-open)
  │    ├─ startForeground(mic)    <- only after RECORD_AUDIO confirmed
  │    ├─ sendTextTurn(prime)     <- client text turn (since 0.2.8; NOT a systemInstruction)
  │    └─ AudioCapture: AudioRecord 16kHz mono PCM16 -> Chunker (20ms frames)
  │         -> BoundedAudioQueue -> audioJob -> session.sendAudio(chunk)
  ├─ onLiveEvent(): collects TranscriptCandidates / TurnComplete / Failed
  ├─ selectAndInsert(): TranscriptSelector (cleaned->raw, rejects garbage)
  └─ sendInsert() -> Messenger IPC to accessibility process

:accessibility process (WhisperTypeAccessibilityService)
  ├─ EditorTracker: focused field + keyboard visible -> show bubble (TYPE_APPLICATION_OVERLAY)
  └─ on insert command: commitText into focused field, verify, reply result
```

Wire (`GeminiLiveWire`, kotlinx-serialization):
- setup: `{"setup":{"model":"models/gemini-3.1-flash-live-preview","generationConfig":{"responseModalities":["AUDIO"]},"inputAudioTranscription":{},"outputAudioTranscription":{}}}`
- audio: `{"realtimeInput":{"audio":{"data":"<b64>","mimeType":"audio/pcm;rate=16000"}}}` (640-byte / 20 ms frames)
- turn end: `{"clientContent":{"turnComplete":true}}`
- prime (0.2.8+): `{"clientContent":{"turns":[{"role":"user","parts":[{"text":"..."}]}]}}` (no turnComplete)
- server messages are **binary frames**; parsed into `ServerContent(inputTranscription, outputTranscription, textParts, turnComplete, interrupted)`.

Robustness added along the way:
- Finalize timeout (8 s) so the panel never hangs on Finalizing.
- Trailing-transcript grace (2 s) after `turnComplete`.
- Echo trust gate (0.2.10): `outputTranscription` candidates are only accepted after `inputTranscription` fired once in the session.
- TranscriptSelector rejects model preambles, greetings, short acknowledgments, Devanagari-in-Latin, corruption, repetition.
- Version bump per commit (`CONTRIBUTING.md` §Versioning); `docs/PUSH_TO_DEVICE.md` records the device reference.

---

## 3. Version history — every strategy shipped today

| Ver | Commit | Change | Result on device |
| --- | --- | --- | --- |
| 0.2.1 | `c153b9b` | **Binary-frame fix**: `onMessage(WebSocket, ByteString)` routed to the text parser. | Resolved the original "101 then silent" bug — `setupComplete` now arrives. |
| 0.2.1 | `a168397` | Finalize timeout, trailing-transcript grace, mic diagnostics (`peakAmp`/`chunksSent`), versioning docs. | No more Finalizing hang. |
| 0.2.2 | uncommitted | FGS crash fix: start as `specialUse`, promote to `specialUse\|microphone` only after permission. | Fresh-install crash gone. |
| 0.2.3 | uncommitted | Wired real mic level: `peakChunkRms` (RMS of bytes actually sent) + `peakAmp` from `AudioCapture.amplitude`. | Proved the mic captures loud, clear speech (RMS 5000–14000). |
| 0.2.4 | uncommitted | Staged on-device protocol (`docs/ON_DEVICE_TEST_PROTOCOL.md`) + `STAGE:` log markers + bubble-shown log. | Stage-by-stage verification possible. |
| 0.2.5 | uncommitted | Echo **systemInstruction** + `outputAudioTranscription` + `outputTranscription` parsing. | `outputTranscription` never fired (suppressed). |
| 0.2.6 | uncommitted | Echo instruction rephrased (v1). | Same — suppressed. |
| 0.2.7 | uncommitted | **Removed systemInstruction** (proven to suppress `outputTranscription`). | `outputTranscription` fired: model **greeted** ("Hello! I'm...") → inserted as text (wrong). |
| 0.2.8 | uncommitted | Dictation **prime text turn** (client turn, not systemInstruction) + greeting rejection. | **First real success**: `inputTranscription="Hello hello test test test."` inserted. Later attempts: silence or ack. |
| 0.2.9 | uncommitted | Reject acknowledgments + prime says "do not acknowledge this instruction". | Model still acked: "I understand." inserted (wrong). |
| 0.2.10 | uncommitted | **Echo trust gate**: `outputTranscription` accepted only after `inputTranscription` fired; ack scoping (short only). | Session: model completely silent → `Selection: NONE`. |
| 0.2.11 | uncommitted | **Audio pacing fix**: removed 50 ms loop gap → continuous real-time 20 ms frames. | **NOT VERIFIED** (device went offline before a test). |

---

## 4. Device log evidence (key excerpts)

**Wire + setup work (every session):**
```
OkHttpGeminiLiveSession: onOpen code=101 ...
OkHttpGeminiLiveSession: setupSent=true setup={"setup":{"model":"models/gemini-3.1-flash-live-preview",...,"inputAudioTranscription":{},"outputAudioTranscription":{}}}
OkHttpGeminiLiveSession: onMessage { "setupComplete": {} }
```

**Mic captures real, loud speech (every session with speech):**
```
FlowRuntimeService: stopDictation: peakAmp=0.26 chunksSent=240 peakChunkRms=14205
FlowRuntimeService: stopDictation: peakAmp=0.123 chunksSent=192 peakChunkRms=8724
FlowRuntimeService: stopDictation: peakAmp=0.153 chunksSent=240 peakChunkRms=8551
```
(Reference: the host's own `real.pcm` file is RMS ≈ 9356.)

**The ONLY on-device success (0.2.8, 22:18):**
```
22:18:35.256 STAGE: dictation prime sent
22:18:35.583 STAGE: first audio chunk sent
22:18:38.427 OkHttpGeminiLiveSession: serverContent: inputTranscription=Hello hello test test test. outputTranscription=null
22:18:38.433 OkHttpGeminiLiveSession: serverContent: inputTranscription=null outputTranscription=Hello hello
22:18:48.130 FlowRuntimeService: Finalize timeout after 8000ms; forcing selection (candidates=2)
22:18:48.133 FlowRuntimeService: Selection: Raw text=Hello hello test test test.
22:18:48.216 FlowRuntimeService: STAGE: insertion result=Inserted
```
Note: `turnComplete` never arrived; the 8 s finalize timeout forced the selection. The transcript was **exact**.

**Typical failure (0.2.7 era, 22:13) — pipeline works, model greets:**
```
22:13:27.119 FlowRuntimeService: Selection: Raw text=Hello! I'm
22:13:27.180 FlowRuntimeService: STAGE: insertion result=Inserted   <- wrong text inserted
```

**Echo suppressed by systemInstruction (0.2.6 era, 21:21):**
```
stopDictation: ... chunksSent=192 peakChunkRms=8724
OkHttpGeminiLiveSession: serverContent: inputTranscription=null outputTranscription=null ... (x18, model audio parts)
21:21:31 Selection: NONE
```

**Silent model (0.2.10, 22:29):**
```
stopDictation: peakAmp=0.096 chunksSent=328 peakChunkRms=6432
(no serverContent with any transcript at all)
Selection: NONE
```

---

## 5. Host-side experiments (VPS, read-only probes — the decisive isolations)

| # | Experiment | Result |
| --- | --- | --- |
| 1 | 101 response header dump (OkHttp) | gfe does **not** echo `Sec-WebSocket-Extensions` → no compression → permessage-deflate exonerated. |
| 2 | `java.net.http` + `onBinary` | Receives `setupComplete` (~1 s). Java clients work. |
| 3 | Python `websocket-client` raw trace | **Every server→client message is a binary frame (opcode 0x2)**; Python's `recv()` didn't care — this was the original "server never responds" bug. |
| 4 | `inputAudioTranscription` schema | `googleapis` proto (`generative_service.proto`, v1beta) confirms `input_audio_transcription = 10` and `serverContent.input_transcription`; the setup shape is correct. |
| 5 | `["AUDIO","TEXT"]` modalities | **Rejected** — connection dropped at setup. Voice-only model never emits text parts. |
| 6 | OkHttp on host, no instruction, audio + `outputAudioTranscription` | `outputTranscription` **fires** (model "Current time is Wednesday," captured). |
| 7 | OkHttp on host, **with echo systemInstruction**, same audio | `outputTranscription` **= 0** (suppressed) despite 8 `modelTurn` messages. → **The systemInstruction suppresses `outputTranscription`.** |
| 8 | `inputTranscription` reliability (4 consecutive runs, `real.pcm`, exact report recipe) | `inputTranscription=0` in all 4; `outputTranscription=4` in all 4. |
| 9 | REST `generateContent` (`gemini-flash-latest`, WAV input) | **Transcribes every file perfectly** ("The birch canoe slid on the smooth planks", "The standard definition of love", etc.). Audio is fine; the failure is Live-specific. |
| 10 | `gemini-2.0-flash` generateContent | HTTP **429 quota exceeded**. `gemini-flash-latest` works. |
| 11 | Echo via client text turn (no systemInstruction) | Model greets / reads the prompt aloud (which *is* captured by `outputTranscription`); does not reliably echo the audio content. |

Audio-file content verified: `real.pcm` actually says **"The standard definition of love"** — the
earlier failure report's claim that it said "The birch canoe..." was inaccurate. All five PCM files
are intelligible speech (REST transcribes all of them).

---

## 6. What works / what does not (stage-by-stage)

| Stage | Status | Evidence |
| --- | --- | --- |
| App installs, no crash | **WORKS** | FGS `specialUse` fix; both processes up. |
| Bubble appears over focused field | **WORKS** | `STAGE: bubble shown (eligible target + keyboard)`. |
| WebSocket + `setupComplete` | **WORKS** | `onOpen 101 → setupComplete` (~1 s). |
| Mic captures the user's voice | **WORKS** | `peakChunkRms` 5000–14000; `chunksSent` 100–330. |
| Audio reaches the server | **WORKS** | Model responds to it (audio `modelTurn`), even when it doesn't transcribe. |
| Server returns the user's speech as text (`inputTranscription`) | **BROKEN / intermittent** | Fired **exactly once** on-device (22:18); 0/4 host runs; null in every other device session. |
| Model echo (`outputTranscription`) | **WORKS mechanically, wrong content** | Fires without a systemInstruction, but the model greets/acks instead of repeating dictation. Suppressed by any systemInstruction. |
| `modelTurn` text parts | **IMPOSSIBLE** | Voice-only model rejects TEXT modality. |
| Selection + insertion | **WORKS** | `Selection: Raw …` → `STAGE: insertion result=Inserted` (correct text in the one success; wrong text in greeting/ack runs before the gate). |

---

## 7. Root-cause analysis — current understanding

1. **The app is not the failure.** Every stage is verified: capture (loud real speech), delivery
   (chunks sent, server responds), wire (binary frames, `setupComplete`), insertion. The one
   failing stage is server-side: the Live endpoint only sporadically delivers `inputTranscription`.
2. **`inputTranscription` is intermittent, not dead.** It fired once (session 22:18) with exact
   text. The trigger condition is unknown — content, session timing, or server-side state. 4/4
   host runs and ~15 device sessions otherwise returned null.
3. **The echo fallback is unreliable.** The model's own speech *is* transcribed (`outputTranscription`),
   but only when **no `systemInstruction`** is present (verified host-side), and it says greetings
   / acknowledgments instead of the dictation. When the ASR is silent, its "echo" is never the
   user's words (hence the 0.2.10 trust gate).
4. **A real audio-pacing defect was found and fixed in 0.2.11** (50 ms gap every 160 ms broke the
   streaming ASR's voice-activity detection). This fix is unverified on-device.
5. **The REST (non-Live) path transcribes perfectly** — which would solve this outright, but the
   requirement is Live-model-only (billing constraint). It remains the fallback if the user
   changes that constraint.

---

## 8. Open items / what is left

1. **Verify 0.2.11** (continuous real-time audio) on the device — this is the last unverified fix
   and the most technically-grounded attempt at making the server's ASR fire reliably.
2. If `inputTranscription` stays sporadic, the Live API cannot reliably produce dictation right
   now. Options: (a) accept retries (it *does* succeed — 22:18 proves the exact-insertion path),
   (b) report the regression to Google (it worked per the earlier `FAILURE_GEMINI_WIRE.md` at
   ~13:00), (c) revisit the Live-only constraint.
3. Commit the accumulated work (0.2.2–0.2.11 fixes + diagnostics + docs) once a decision is made.

---

## 9. Files touched / diagnostics

- `app/src/main/java/com/whispertype/android/platform/gemini/OkHttpGeminiLiveSession.kt` (binary frames, outputTranscription, echo gate)
- `app/src/main/java/com/whispertype/android/platform/gemini/GeminiLiveWire.kt` (codec: setup/audio/turnComplete/textTurn/parse)
- `app/src/main/java/com/whispertype/android/platform/gemini/GeminiSessionConfig.kt` (input/outputAudioTranscription flags)
- `app/src/main/java/com/whispertype/android/platform/runtime/FlowRuntimeService.kt` (state machine, prime, timeouts, diagnostics)
- `app/src/main/java/com/whispertype/android/audio/AudioCapture.kt` (pacing fix 0.2.11)
- `app/src/main/java/com/whispertype/android/core/transcript/TranscriptSelector.kt` (preamble/greeting/ack rejection)
- `docs/ON_DEVICE_TEST_PROTOCOL.md`, `docs/PUSH_TO_DEVICE.md`, `CONTRIBUTING.md`
- Host probes: `/tmp/opencode/gemini_*.py`, `/tmp/opencode/okhttptest/OkHttpProbe*.java`, jshell `java.net.http` probes, REST generateContent tests.
