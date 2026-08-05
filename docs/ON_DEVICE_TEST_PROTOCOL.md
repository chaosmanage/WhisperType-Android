# On-Device Dictation Test Protocol (staged, no guessing)

Each stage has an explicit **trigger**, a **log signature** to check, and a
**pass/fail rule**. Run the stages in order. A stage that fails is diagnosed
**at that stage** — do not proceed past a failed stage until it is resolved.

Run every log command from the build container:

```bash
# watch all relevant logs live during the whole test
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat | grep -E "STAGE|OkHttpGeminiLiveSession|FlowRuntimeService|WhisperTypeAccessibility"
```

---

## Stage 0 — Preconditions (automated, no interaction)

| Check | Command | Pass |
| --- | --- | --- |
| Device connected | `adb devices` | `device` (not `offline`) |
| App version | `adb shell dumpsys package com.whispertype.android \| grep version` | matches committed `versionName`/`versionCode` |
| Mic permission | `adb shell dumpsys package com.whispertype.android \| grep RECORD_AUDIO` | `granted=true` |
| Notifications | same, `POST_NOTIFICATIONS` | `granted=true` |
| FGS special-use | same, `FOREGROUND_SERVICE_SPECIAL_USE` | `granted=true` |
| Accessibility enabled | `adb shell settings get secure enabled_accessibility_services` | contains `WhisperTypeAccessibilityService` |
| App starts w/o crash | `adb shell am start -n com.whispertype.android/.MainActivity`; then `logcat -d \| grep FATAL` | no `FATAL EXCEPTION` |
| Both processes | `adb shell pidof com.whispertype.android` and `...:accessibility` | two PIDs |

**If Stage 0 fails**: reinstall (`install -r`), grant permissions, re-enable the
accessibility service, then re-run Stage 0.

---

## Stage 1 — Bubble appears over a focused field

- **Trigger**: open any app with a text field (Notes, a chat field, a search
  box), tap the field so the keyboard is visible.
- **Log signature**: `STAGE: bubble shown (eligible target + keyboard)`
- **Pass**: that line appears (the mic bubble is on screen).
- **If FAIL**: the log instead says `Bubble hidden; reasons=[...]`. That reason
  list is the diagnosis (e.g. `no_editor_focus`, `uncertain_field`,
  `keyboard_hidden`, `secure_field`). Fix that condition and retry.

---

## Stage 2 — Session connects and Gemini acknowledges setup

- **Trigger**: tap the bubble.
- **Log signature** (in order):
  ```
  OkHttpGeminiLiveSession: onOpen code=101
  OkHttpGeminiLiveSession: setupSent=true
  OkHttpGeminiLiveSession: onMessage { "setupComplete": {} }
  ```
- **Pass**: `setupComplete` arrives within a few seconds.
- **If FAIL**: check `onMessage { "setupError" ... }` (bad key/model) or
  `onFailure` (network/TLS). This stage already passes in normal conditions.

---

## Stage 3 — Microphone produces data

- **Trigger**: while the panel shows Listening, start speaking.
- **Log signature**: `STAGE: first audio chunk sent (mic producing data)` then
  periodic `audio progress: chunksSent=N peakChunkRms=...`.
- **Pass**: `STAGE: first audio chunk sent` appears.
- **If FAIL**: the audio loop never sent a chunk → mic init/read problem or the
  capture coroutine is not running. Capture was reached, so this is a
  `AudioCapture`/`AudioRecord` issue.

---

## Stage 4 — The captured audio is real sound (not silence)

- **Trigger**: speak loudly and clearly for 4–5 seconds, then tap **Stop**.
- **Log signature**: `stopDictation: peakAmp=... chunksSent=N peakChunkRms=...`
- **Pass**: `peakChunkRms` is well above 0 (loud speech ≈ 3,000–15,000;
  reference: the host `real.pcm` file is ≈ 9,356). `peakAmp` > ~0.05.
- **If FAIL** (`peakChunkRms` ≈ 0 while the user clearly spoke): the mic is
  capturing silence. Diagnose `AudioCapture.createDefaultSource()` /
  `AudioRecord` on the device (source, sample rate, buffer, FGS promotion).
  This is the moment that distinguishes "app mic broken" from "server issue".

---

## Stage 5 — Server returns a transcript

- **Trigger**: same run, after Stop.
- **Log signature**: `serverContent: inputTranscription=<text> ...`
- **Pass**: an `inputTranscription` with non-empty text appears (before or
  after `turnComplete`; the grace window covers trailing messages).
- **If FAIL**: the server did not transcribe. This is the known server-side
  issue (reproduced on the host with the identical setup and audio). The fix is
  a different transcript source (echo via `outputTranscription`) or waiting for
  the API feature to return — **not** another app/wire change.

---

## Stage 6 — Candidate selection

- **Log signature**: `Selection: Cleaned|Raw text=...` **or** `Selection: NONE`.
- **Pass**: a `Cleaned`/`Raw` selection with the spoken text.
- **If FAIL** (`NONE`): follows from Stage 5 (no candidates) or the
  `TranscriptSelector` rejected the candidate (garbled/preamble heuristics).

---

## Stage 7 — Insertion into the focused field

- **Log signature**: `STAGE: insertion result=Inserted` and the spoken text
  appears in the field.
- **Pass**: text is in the field.
- **If FAIL**: `insert_ambiguous` (could not verify) or a
  `runtime_ipc_failed`/accessibility error → accessibility insertion path.

---

## Decision table

| Stage 3 | Stage 4 | Stage 5 | Diagnosis |
| --- | --- | --- | --- |
| FAIL | — | — | `AudioCapture` never runs (capture path) |
| PASS | FAIL (silent) | FAIL | Mic captures silence (device `AudioRecord`) |
| PASS | PASS (voice) | FAIL | Server `inputTranscription` down → **server-side fix** |
| PASS | PASS (voice) | PASS | Full pipeline; continue to Stage 6–7 |
