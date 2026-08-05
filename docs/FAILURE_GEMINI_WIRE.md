# Rebuild Failure Report — Phase 6 Gemini Live Wire (RESOLVED)

**Date:** 2026-08-05
**Branch / commit:** `rebuild/clean-runtime` (working tree, uncommitted)
**Device:** Samsung Galaxy S25 (`SM-S921B`), Android 16 (SDK 36), 1080x2340 @ 480dpi
**Keyboard:** SwiftKey (`com.touchtype.swiftkey/com.touchtype.KeyboardService`) — default + enabled
**Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>`
**Model:** `gemini-3.1-flash-live-preview` (voice-only Live model)
**Status:** **RESOLVED.** The server was responding normally the whole time; the client discarded its replies. The Gemini Live server sends **every server→client message as a binary WebSocket frame (opcode `0x2`)**. OkHttp routes binary frames to a separate callback — `onMessage(WebSocket, ByteString)` — which `OkHttpGeminiLiveSession` (and the host OkHttp probes) never implemented, so `setupComplete`, `serverContent`, `setupError`, and `goAway` were silently dropped and the session appeared to hang until the idle close `1000`. Fixed by delegating the binary overload to the existing text parser (one method). The earlier conclusion that "OkHttp 5.4.0 is incompatible with the Gemini Live server" (§5) was wrong; each suspect was ruled out by direct wire tests below.

---

## 1. What we are trying to do

Phase 6 of `PRD/WisprFlow-Parity-Rebuild-Plan.md` ("Gemini Live Substitution", §359–372) and
`Implementation_Plan.md` §12 ("Phase 7 — Gemini Live integration", lines 800–834):

1. Keep `FlowRuntimeService` as the active-session owner.
2. Start microphone capture only after explicit bubble interaction.
3. Promote to microphone foreground mode before `AudioRecord`.
4. Await Gemini setup acknowledgement (`setupComplete`) **before** audio.
5. Stream exact 16 kHz mono PCM16 frames in 20 ms chunks.
6. Drain accepted audio before one activity-end boundary (`clientContent.turnComplete`).
7. Validate raw and cleaned candidates; select cleaned first, raw fallback.
8. Pass the selected candidate to the already-proven accessibility insertion transaction.
9. Samsung gate: one English and one Latin-script Hinglish utterance insert correctly into SwiftKey fields.

The voice-to-text source is the server's **`inputTranscription.text`** — the transcript of what the *user* said — not the model's own (audio) output. The PRD's companion wire spec is `docs/GEMINI_LIVE_PROTOCOL.md`.

---

## 2. What is actually failing

### 2.1 The blocking symptom (both on-device and host-side OkHttp)

The WebSocket **connects** (OkHttp `onOpen code=101`), the app **sends the setup message** (`setupSent=true`), but no server response ever surfaces — no `setupComplete`, no `serverContent`, no `goAway`. After ~11–17 s the server closes the socket cleanly with `code=1000`, empty reason.

**Why it looked like the server was silent:** the Gemini Live server sends every server→client message as a **binary WebSocket frame** (opcode `0x2`; the payload is the JSON text). OkHttp invokes a *different* callback for binary frames — `onMessage(WebSocket, ByteString)` — and the session only implemented the text-frame callback (`onMessage(WebSocket, String)`). Every server message was therefore received by the client and then silently discarded. The server was responding normally the entire time.

The app's `awaitReady()` (`withTimeout(READY_TIMEOUT_MS=15s)`) therefore times out and surfaces:

```
Timed out waiting for the Gemini session to start.
```

On-device logcat (wire-level logging added to `OkHttpGeminiLiveSession`, pid 28470):

```
I OkHttpGeminiLiveSession: onOpen code=101 url=wss://generativelanguage.googleapis.com/ws/.../BidiGenerateContent (auth query param redacted)
I OkHttpGeminiLiveSession: setupSent=true setup={"setup":{"model":"models/gemini-3.1-flash-live-preview","generationConfig":{"responseModalities":["AUDIO"]},"inputAudioTranscription":{}}}
   (no onMessage ever)
I OkHttpGeminiLiveSession: onClosing code=1000 reason= at=...  (~11-17 s later)
I OkHttpGeminiLiveSession: onClosed code=1000 reason=
```

There are **no** `onMessage` / `onFailure` / `onOpen`-retry lines. The connection is accepted (101) and then silently starved.

### 2.2 The same failure reproduces on the host with OkHttp

A standalone JVM probe using the app's exact OkHttp client (OkHttp 5.4.0, `pingInterval=20s`, `readTimeout=0`) against the live endpoint with the working key produces the identical result:

```
[HOST-OKHTTP] onOpen code=101 at 323ms
[HOST-OKHTTP] setupSent=true
[HOST-OKHTTP] completed=false opened=true in 20046ms     <- no onMessage, socket still open
```

Repeated with explicit `protocols=[HTTP_1_1]`, with a browser `User-Agent`, and with no `pingInterval` — **always the same** (onOpen 101, setupSent true, no response).

This ruled out: the phone's network, Wi-Fi, Jio 5G, Tailscale, AdGuard, Proton VPN, the stored-key path, the device's TLS stack, and the setup JSON content. The isolation was real, but the conclusion drawn from it — "OkHttp 5.4.0's WebSocket client is incompatible with this server" — was **wrong**: the host probes, like the app, only implemented the text `onMessage`, so they discarded the server's binary frames for exactly the same reason.

### 2.3 The same handshake succeeds with Python websocket-client (host)

`websocket-client` 1.9.0, same URL, same setup JSON, same key, same audio — receives `setupComplete` in **0.23 s** and later receives the transcription:

```
[SETUP] OK
[HOST] setupComplete in 0.23s: OK
[RESULT] ... inputTranscription=1 outputTranscription=1
[INPUT-FULL] The birch canoe slid on the smooth planks.
```

So: **the API, the key, the model, and the exact JSON setup all work.** Only the client library differs.

---

## 3. All tests performed and their results

### 3.1 Host-side (Python `websocket-client` 1.9.0) — the reference that WORKS

Scripts under `/tmp/opencode/gemini_*.py`. Common wire (the one that succeeds):

```
setup:    {"setup":{"model":"models/gemini-3.1-flash-live-preview",
                    "generationConfig":{"responseModalities":["AUDIO"]},
                    "inputAudioTranscription":{}}}
audio:    {"realtimeInput":{"audio":{"data":"<base64 PCM16>","mimeType":"audio/pcm;rate=16000"}}}
turnEnd:  {"clientContent":{"turnComplete":true}}
```

| Test / audio used | Result |
| --- | --- |
| `gemini_live_probe.py` — connect + setup with **invalid key** | `onOpen` OK; server closes `1008` "API key not valid. Please pass a valid API key." → confirms server rejects bad keys via close frame, not JSON. |
| `gemini_verify.py` — connect + setup with **valid key** | `setupComplete in 0.23s` — **OK**. |
| `gemini_full_test3.py` — setup + espeak audio "Hello this is a voice dictation test for WhisperType" + turnComplete | `setupComplete` OK; server returns `modelTurn` audio + `outputTranscription` ("Hello! How can I help you today?") but **`inputTranscription` never appears**. Model treated synthetic espeak as a conversational prompt. |
| `gemini_prd2.py` — setup + **`realtimeInput.config` message** (old protocol doc §2.2) + **`mediaChunks`** audio shape | **Connection lost** (server rejects the `realtimeInput.config` / `mediaChunks` shape on this API). |
| `gemini_prd3.py` — setup + `realtimeInput.config` message + `realtimeInput.audio` | **Connection lost**. |
| `gemini_echo.py` / `gemini_sysinstr.py` / `gemini_stream.py` — various systemInstructions + espeak | `setupComplete` OK but model "role-plays"/converses; `inputTranscription` never fires; some runs dropped the socket. |
| `gemini_seq.py` / `gemini_modern.py` — no systemInstruction, espeak audio | `setupComplete` OK; `outputTranscription` = conversational reply; **`inputTranscription` = 0**. |
| `gemini_clean.py` / `gemini_clean2.py` / `gemini_real.py` — **real human speech** | `setupComplete` OK; `inputTranscription` fires **exactly once**: `{"serverContent":{"inputTranscription":{"text":"The birch canoe slid on the smooth planks."}}}` — **the dictation source works**. |

**Conclusion from host testing:** `inputTranscription.text` is the correct field and does fire with real speech; it is a separate `serverContent` message (own message, "no guaranteed ordering"); it requires real, recognizable speech audio.

### 3.2 Host-side (OkHttp 5.4.0 JVM) — reproduces the failure

Standalone Java probes `/tmp/opencode/okhttptest/OkHttpProbe*.java`, same client config as the app.

| Probe | Variation | Result |
| --- | --- | --- |
| `OkHttpProbe` | default client (pingInterval 20s, readTimeout 0) | onOpen 101, setupSent true, **no onMessage in 20s** |
| `OkHttpProbe2` | prints server response headers | onOpen 101 (`X-Client-Wire-Protocol: HTTP/1.1`, `Server-Timing: gfet4t7`), setupSent true, **no onMessage in 15s** |
| `OkHttpProbe3` | `protocols=[HTTP_1_1]`, no pingInterval | onOpen 101, setupSent true, **no onMessage in 12s** |
| `OkHttpProbe4` | browser `User-Agent` header | onOpen 101, setupSent true, **no onMessage in 10s** |

Every OkHttp variation failed identically — because none of the variations implemented the binary `onMessage`; the server's binary frames were discarded in all of them. Python succeeded because `websocket-client`'s `recv()` returns message bytes regardless of frame type (its trace shows the server's frame was binary: `++Rcv raw: b'\x82\x1a{...setupComplete...}'`, where `0x82` = FIN + binary opcode). Adding a binary-frame handler to a `java.net.http.WebSocket` probe made the **same client** that had "failed" for 30 s receive `setupComplete` in ~1 s — the decisive confirmation.

### 3.3 On-device (Android app) — same failure

| Step | Evidence |
| --- | --- |
| Install `app-debug.apk` (`0a6b9a25...`, then `6807cbaa...`) | `Success` |
| Key management in Settings | stale blob cleared (`No API key set`), user pasted the working key, `Save key` → `API key configured`. (Server-side close in the very first on-device run — "API key not valid" — was from the stale blob.) |
| Bubble → dictation | bubble visible (`content-desc="Start dictation"`, window `[936,1075][1080,1219]`), tap expands panel `144x144 → 311x368`. |
| Mic foreground | `isForeground=true types=0x00000080`; `AudioService: recording activity received`. |
| Wire | `onOpen code=101`, `setupSent=true`, **no onMessage**, `onClosing code=1000` after ~11–17 s, UI: `Timed out waiting for the Gemini session to start.` |

---

## 4. Different techniques / hypotheses tried (and their outcomes)

1. **Deprecated model name.** Original code used `gemini-2.5-flash-live-preview`, shut down 2025-12-09. → **Fixed** to `gemini-3.1-flash-live-preview` (confirmed current, voice-only). Setup still hung → not the root cause.
2. **`responseModalities:["TEXT"]`.** Server error: *"the requested combination of modalities is not supported by the model"* (voice-only model). → **Fixed** to `["AUDIO"]` + `inputAudioTranscription:{}`. Host Python then works → correct; not the root cause.
3. **Missing transcription enablement.** Added `inputAudioTranscription:{}` in setup (current API) and tried the old `realtimeInput.config` transcription message (protocol doc §2.2). The old message shape is rejected by this API (connection lost). Setup-level `inputAudioTranscription` is correct.
4. **Audio shape `mediaChunks` vs `realtimeInput.audio`.** Protocol doc §2.4 says `mediaChunks`; the live API (get-started doc) uses `realtimeInput.audio`. `mediaChunks` drops the connection; `realtimeInput.audio` works.
5. **Wrong transcript field.** Was reading `modelTurn.parts[].text` (model's own output, which is audio for this model). → **Fixed** to prioritize `inputTranscription.text` (what the user said). Host-verified with real speech.
6. **Swallowed setup timeout.** `withTimeout` throws `TimeoutCancellationException` (a `CancellationException`), which `runLiveSession` rethrew silently → panel stuck on "Starting" forever. → **Fixed**: `awaitReady` converts the timeout to a typed `GeminiLiveException`; server-close-before-setup handled in `onClosing`/`onClosed`. UI now correctly reports the timeout.
7. **Stale / expired on-device key.** First on-device run closed with "API key not valid" → stale blob. → **Fixed** by clearing and re-entering the working key.
8. **Network interception (Tailscale / AdGuard / Proton / Wi-Fi).** Ruled out: default route is `wlan0` (local gateway, masked), not `tun0`; `tun0` (Tailnet CGNAT address, masked) only routes Tailnet `100.x` subnets; **host-side OkHttp fails identically** while Python succeeds on the same host network.
9. **OkHttp protocol negotiation / headers / User-Agent.** Tried HTTP/1.1-only, browser UA, no pingInterval — all fail identically.
10. **`android.util.Log` crashing JVM unit tests.** Direct `Log` calls in the session broke host tests (stub `RuntimeException: Stub!`). → **Fixed** with a JVM-safe `GeminiLog` wrapper (reflection, no-op on JVM).

---

## 5. Root cause (confirmed by direct wire tests)

**The Gemini Live server encodes every server→client message as a binary WebSocket frame (opcode `0x2`), regardless of what the client sends.** The client code listened only for text frames:

- OkHttp invokes `onMessage(WebSocket, String)` for text frames and a **separate** `onMessage(WebSocket, ByteString)` overload for binary frames. `OkHttpGeminiLiveSession` only overrode the text one, so `setupComplete` (and every later `serverContent`) was silently dropped. The same omission existed in the host OkHttp probes.
- A `java.net.http.WebSocket` probe reproduced the identical failure and recovered instantly once `onBinary` was implemented (`setupComplete` in ~1 s).
- Python's `websocket-client` never distinguishes frame types in `recv()`, which is why it always worked and why the binary opcode was only visible in its raw-frame trace (`++Rcv raw: b'\x82\x1a{...setupComplete...}'`; `0x82` = FIN + binary opcode).

### Ruled out by direct test (all negative)

1. **`permessage-deflate`** — gfe does not echo `Sec-WebSocket-Extensions` in the 101 response (header dump), so OkHttp never compresses frames. `java.net.http` sends no extensions at all and failed identically.
2. **HTTP/2 / h3 / ALPN** — `protocols=[HTTP_1_1]` (OkHttp) and `HttpClient.Version.HTTP_1_1` (JDK) still failed; Python with `sslopt={'alpn_protocols':['h2','http/1.1']}` still succeeded in 0.25 s.
3. **`Origin` header** — JDK + `Origin: https://generativelanguage.googleapis.com` still failed; Python with `suppress_origin=True` still succeeded in 0.24 s.
4. **Frame masking / length encoding** — captured first frames are structurally identical (`81 fe 00 8b` + 4-byte mask + 139-byte payload) for OkHttp, JDK, and Python.
5. **User-Agent / pingInterval / protocols variations** — changed nothing, because the binary frames were discarded in every case.

---

## 6. Status after the fix

1. **Fixed in code:** `OkHttpGeminiLiveSession.onMessage(WebSocket, ByteString)` now delegates to the text parser (`bytes.utf8()`), so binary server frames (`setupComplete` / `serverContent` / `setupError` / `goAway`) are handled through the existing path. Unit tests now push server messages as binary frames to mirror the live endpoint; `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` all pass.
2. **Remaining:** re-run the on-device Phase 6 Samsung gate (English + Hinglish into SwiftKey) with the fixed APK to confirm end-to-end insertion.
3. Commit the accumulated fixes (binary-frame handling, model name, AUDIO modality + `inputAudioTranscription`, `inputTranscription` priority, timeout/close handling, `GeminiLog`, model override in Settings).

---

## 7. References

- `PRD/WisprFlow-Parity-Rebuild-Plan.md` — Phase 6 (§359–372), Builder Protocol (§6).
- `Implementation_Plan.md` §12 (Gemini Live integration protocol flow, lines 800–834); §18.2 Gemini contract tests.
- `docs/GEMINI_LIVE_PROTOCOL.md` — the wire spec; note it is **partially outdated** for the current API: `realtimeInput.config` and `mediaChunks` are rejected by the live `gemini-3.1-flash-live-preview` endpoint; the working shapes are setup-level `inputAudioTranscription` + `realtimeInput.audio`. Server→client messages arrive as **binary WebSocket frames** (opcode `0x2`) and must be handled on the client's binary-frame callback. The `inputTranscription.text` field (user's speech) is confirmed correct.
- Google docs (fetched 2026-08-05): `ai.google.dev/api/live` (BidiGenerateContentSetup/ServerContent schema), capabilities page (audio transcriptions, turn coverage default `TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO`).
