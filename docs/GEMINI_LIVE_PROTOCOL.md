# Gemini Live Protocol (Workstream B)

This document describes the wire protocol, lifecycle, and quality chain of the
Gemini Live dictation session. It is the companion to Implementation Plan §12–§14.

## 1. Endpoint and model

- WebSocket URL:
  `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<apiKey>&model=<modelId>`
- The API key is URL-encoded and passed as the `key` query parameter. It is never logged.
- The model identifier lives **only** in `GeminiSessionConfig.DEFAULT_MODEL_ID`
  (`gemini-2.0-flash-live-001`). Changing it must be a one-line change accompanied by a
  contract-test update (`GeminiLiveClientContractTest` pins the model in the request).

## 2. Session lifecycle

```
connect() ──► handshake ──► send setup ──► send realtimeInput.config
         ──► (audio chunks) ──► sendActivityEnd() ──► TurnComplete ──► SessionEnd ──► close
```

1. **Setup** (sent once, first message on the wire):
   ```json
   {
     "setup": {
       "model": "models/gemini-2.0-flash-live-001",
       "generationConfig": { "responseModalities": ["AUDIO"] },
       "systemInstruction": { "parts": [{ "text": "<dictation instruction>" }] }
     }
   }
   ```
   The system instruction selects English or Latin-script Hinglish dictation behavior.
2. **Realtime input configuration** (second message):
   ```json
   {
     "realtimeInput": {
       "config": {
         "audio": { "sampleRateHertz": 16000 },
         "transcription": { "cleanedOutputTranscription": true, "rawTranscription": true }
       }
     }
   }
   ```
3. **Server acknowledgement**: `{"setupComplete":{}}`. Any model content before this is a
   protocol error (`PROTOCOL_ERROR`).
4. **Audio**: one 20 ms PCM16 mono chunk per message, base64-encoded:
   ```json
   {
     "realtimeInput": {
       "mediaChunks": [{ "mimeType": "audio/pcm;rate=16000", "data": "<base64>" }]
     }
   }
   ```
   Chunks sent after `sendActivityEnd()` are dropped.
5. **Activity end** (exactly one message, further calls are ignored):
   ```json
   { "clientContent": { "turnComplete": true } }
   ```
6. **Completion**: the next `serverContent` carrying `turnComplete: true` produces
   `TurnComplete` followed by `SessionEnd`, then the client closes the socket cleanly
   (code 1000).

## 3. Server messages

| Field | Meaning |
| --- | --- |
| `setupComplete` | Handshake acknowledged; session is open. |
| `serverContent.modelTurn.parts[].text` | Cleaned transcript delta. |
| `serverContent.modelTurn.parts[].inlineData` with `mimeType: text/plain` | Raw transcript delta (base64). Audio inlineData is ignored. |
| `serverContent.inputTranscription.text` / `outputTranscription.text` | Robustness fallback sources for raw/cleaned deltas when part text is absent. |
| `serverContent.turnComplete: true` | Model finished the turn. |
| `serverContent.interrupted: true` / top-level `interrupted: true` | Dictation was interrupted; unrecoverable (recoverable `INTERRUPTED` failure). |
| `goAway` | Server-initiated drain; client closes the socket cleanly, no failure. |
| `error` | Failure; `status`/`code` mapped to `AUTH_ERROR` (unauthenticated/permission denied/400–403) or `SERVER_ERROR`. |

Any message type not listed (including future `toolCall`, `usageMetadata`) is logged as
unhandled and ignored.

## 4. Transcript streams and completion

- **Cleaned** (transcripts of what the model says, verbatim words without punctuation) and
  **raw** (transcripts of the user's speech) are accumulated separately in arrival order.
- At `turnComplete`, cumulative candidates are delivered in `TurnComplete`, and at session
  end the final candidates are delivered in `SessionEnd` — the caller reads candidates from
  `SessionEnd`.
- A session that never produced a delta delivers `null` candidates (callers treat null as
  "no transcript").

## 5. Failures, timeouts, retry policy

- Exactly one `Failed` event per session; afterwards the connection closes itself.
- `sendActivityEnd()` starts a `GeminiSessionConfig.activityEndTimeoutMillis` timer; if the
  server produces no `turnComplete` within the window the session fails with `TIMEOUT`
  (recoverable).
- **Never retry after any audio has been transmitted** (§12). The client performs no
  reconnect, no resend, and no auto-recovery; the caller observes `Failed` and starts a new
  session if appropriate.
- `close()` is safe to call at any time and cancels the session without emitting further
  events.

## 6. JSON encoding (deviation from "org.json")

The wire messages are encoded and parsed by a small dependency-free codec
(`GeminiJson`/`JsonValue` inside `GeminiLiveProtocol.kt`) rather than `org.json`.

Rationale: JVM unit tests run against the Android mockable `android.jar`, where `org.json`
is stubbed (`java.lang.RuntimeException: Stub!`). The task constraints forbid editing
Gradle files (no `testImplementation("org.json:json:...")`, no
`unitTests.returnDefaultValues`), and no kotlinx-serialization is permitted. The hand-rolled
codec keeps production behavior identical to the org.json contract while allowing the exact
public API to be unit-tested. The codec is strict: malformed frames fail the session with
`PROTOCOL_ERROR`.

## 7. Candidate validation and selection chain

After `SessionEnd`, the caller runs the candidates through `CandidateSelector`:

1. **Cleaned** candidate → `CleanedTranscriptValidator` (corruption rules + cleaned≤3×raw
   length + no asterisk markers) and `HinglishValidator` (Latin-script-only) → valid?
2. If not, **raw** candidate → `TranscriptValidator` + `HinglishValidator` → valid?
3. Otherwise → `Rejected` with `NO_VALID_TRANSCRIPT` (recoverable).

`CorruptionRules` heuristics (documented in the file): empty/whitespace/punctuation-only/
symbol-only output, repeated dot/pipe runs, model-style conversational preambles,
implausible single-utterance length (>250 words / >1,800 chars — a 60-second recording at a
4 words/second ceiling), repeated single-character runs, control characters, and
candidate-duplication (one candidate a pure repetition of the other). URLs, emails, code
identifiers, numbers, emoji, and Latin Hinglish code-switching are allowed.
