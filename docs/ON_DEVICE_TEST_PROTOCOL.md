# On-Device Dictation Test Protocol (staged, no guessing)

Each stage has an explicit **trigger**, a **log signature** to check, and a
**pass/fail rule**. Run the stages in order. A stage that fails is diagnosed
**at that stage** — do not proceed past a failed stage until it is resolved.

The runtime logs a single aggregate line per finished session:
```
SESSION DONE outcome=<Success|Error|Cancelled> tapToCapture=…ms setup=…ms
tapToFirstAudio=…ms firstAudioToFirstTranscript=…ms stopToQuiesce=…ms
stopToActivityEnd=…ms stopToSettled=…ms stopToInsert=…ms insertToResult=…ms
captured=… accepted=… rejected=… maxQueue=… inputTx=… outputTx=…
turnComplete=… hardDeadline=… overflow=… [reject=<rule>] [lenient=true]
```
(see `docs/GEMINI_LIVE_TRANSCRIPTION.md` §11 for the meaning of each field).

Run every log command from the build container:

```bash
# watch all relevant logs live during the whole test
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat | grep -E "SESSION DONE|serverContent|STAGE|OkHttpGeminiLiveSession|FlowRuntimeService|WhisperTypeAccessibility"
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
- **Log signatures** (in order):
  ```
  OkHttpGeminiLiveSession: onOpen code=101 url=wss://...?key=<redacted>
  OkHttpGeminiLiveSession: setupSent=true
  ```
  followed by `SESSION DONE ... setup=NNNms ...` (setup = tap-to-setupComplete)
  and, while listening, `serverContent: inputTx=… outputTx=… textParts=… turnComplete=…`.
- **Pass**: a `SESSION DONE` line appears with a plausible `setup=` duration; if
  setup failed you see `outcome=Error` with `reject=` absent and the error UI.
- **If FAIL**: check `setupError` in the logs (bad key/model) or `onFailure`
  (network/TLS). A `setup=` duration above ~15 s implies the 15 s ready timeout.

---

## Stage 3 — Microphone produces data

- **Trigger**: while the panel shows Listening, start speaking.
- **Log signature**: `SESSION DONE ... captured=N accepted=N rejected=0 ...`
  with `captured`/`accepted` well above 0, plus live `serverContent:` frames.
- **Pass**: `accepted` > 0 (audio frames reached the session).
- **If FAIL** (`accepted=0`): the audio loop never sent a frame → mic
  init/read problem or the capture coroutine is not running (an
  `AudioCapture`/`AudioRecord` issue). Check for an `outcome=Error` with
  `runtime_mic_permission` or a capture-read failure.

---

## Stage 4 — The captured audio is real sound (not silence)

- **Trigger**: speak loudly and clearly for 4–5 seconds, then tap **Stop**.
- **Log signature**: `SESSION DONE ... inputTx=N ...` and (on success)
  `outcome=Success ... stopToInsert=…ms`.
- **Pass**: the text appears in the field. If it does, the mic path is proven.
- **If FAIL** (`outcome=Error`, `inputTx=0`, `reject=` absent): the server
  returned no `inputTranscription` within the hard deadline → either the mic
  captured silence or the Live ASR did not fire. Cross-check with the REST
  `generateContent` path on the same audio (known to transcribe correctly) to
  distinguish "mic broken" from "Live ASR silent".

---

## Stage 5 — Server returns a transcript

- **Trigger**: same run, after Stop.
- **Log signature**: `SESSION DONE ... inputTx=1 ... outcome=Success` (or an
  `Error` with `reject=<rule>`).
- **Pass**: `inputTx>=1`. With 0.3.x this is expected in nearly every session.
- **If FAIL** (`inputTx=0`): the server did not transcribe this session — the
  known Live-model intermittency. With a `reject=<rule>` present, the transcript
  arrived but the selector rejected it (see `docs/GEMINI_LIVE_TRANSCRIPTION.md`
  §8): `reject=DEVANAGARI` in English mode, or `reject=GARBLED`.

---

## Stage 6 — Candidate selection

- **Log signature**: `SESSION DONE outcome=Success` (inserted) vs
  `outcome=Error` + `reject=<rule>`.
- **Pass**: `outcome=Success` with the spoken text inserted.
- **If FAIL** (`reject=` present): the transcript arrived but was rejected.
  `lenient=true` means the failsafe still inserted it. Any remaining rejection
  should be one of blank / punctuation-only / garbled / Devanagari-in-English.

---

## Stage 7 — Insertion into the focused field

- **Trigger**: after a successful session, check the field.
- **Log signature**: `SESSION DONE outcome=Success ... stopToInsert=…ms insertToResult=…ms`.
- **Pass**: the spoken text is in the field.
- **If FAIL**: `outcome=Error` with `insert_ambiguous` (could not verify), or a
  `runtime_no_accessibility` / `runtime_ipc_failed`-style failure → accessibility
  insertion path.

---

## Stage 8 — Freely draggable mic bubble

- **Trigger**: with the bubble visible over a focused field, drag it to a new
  position; end the session; refocus the field.
- **Pass**: the bubble reappears at the dragged position after refocus, and that
  position persists across sessions and device restarts. **Tap still starts** a
  session from the new position (a `SESSION DONE` line appears).
- **If FAIL**: the position resets on refocus → persistence is broken, or the
  bubble is no longer tappable after dragging.

## Stage 9 — Capsule recording UI + animated circular waveform

- **Trigger**: tap the bubble and start speaking.
- **Log signature**: `SESSION DONE ... outcome=Success ...` as before.
- **Pass**: the keyboard area is replaced by the capsule recording panel showing
  a live animated circular waveform; Stop and Cancel controls are present and
  tappable; the waveform animates while audio is captured and stills after Stop.
- **If FAIL**: the panel is missing, the waveform is static, or Stop/Cancel is absent.

## Stage 10 — Auto-stop (silence + hard cap)

- **Trigger**: set `Auto-stop timeout`, begin dictating, stop speaking, and do
  not touch the panel.
- **Log signature**: `SESSION DONE outcome=Success ...` with no manual Stop — the
  panel ends the session on its own.
- **Pass**: for each option (15/30/60/120/300 s, default 60 s) the session stops
  automatically after the silence interval, and a hard cap stops any session that
  reaches the cap even if audio is still flowing.
- **If FAIL**: the session never auto-stops, or stops at the wrong interval.

## Stage 11 — Output polish levels

- **Trigger**: set `Output polish` to None, Low, Medium, and High in turn, and
  dictate the same phrase with filler words / disfluencies at each level.
- **Log signature**: `SESSION DONE ... outcome=Success ...` (the level is applied
  via the Gemini `systemInstruction`; see `docs/GEMINI_LIVE_TRANSCRIPTION.md`).
- **Pass**: None returns the raw transcript, High returns the most cleaned text,
  and Low/Medium sit between — the four levels are distinguishable from each other.
- **If FAIL**: the levels produce identical output.

## Stage 12 — Custom dictionary corrections

- **Trigger**: add a word plus an optional `Always write as` correction in
  `Settings → Custom dictionary`; dictate the uncorrected form.
- **Log signature**: `SESSION DONE ... outcome=Success ...`.
- **Pass**: the inserted text uses the corrected spelling (applied at insertion,
  word-boundary, case-insensitive); the live transcript is not rewritten mid-session.
- **If FAIL**: the correction is not applied, or it is applied mid-stream.

## Stage 13 — History list / copy / delete / delete-all

- **Trigger**: enable `Local history`, complete a dictation, then open
  `Settings → Privacy and history → View history`.
- **Log signature**: none required (storage-only path).
- **Pass**: the completed transcript appears in the list; Copy copies the text;
  Delete removes one entry; `Clear all history` empties the list; entries respect
  the retention period.
- **If FAIL**: an entry is missing, copy/delete misbehaves, or retention is not honored.

## Stage 14 — Android 13+ first-install permission flow (tablet)

- **Trigger**: on an Android 13 or newer tablet, install the 0.4.0 build for the
  first time and complete setup.
- **Log signature**: permission grants reflected in
  `dumpsys package com.whispertype.android` for `RECORD_AUDIO` and
  `POST_NOTIFICATIONS`.
- **Pass**: the runtime prompts appear in order (microphone, then notifications);
  granting both makes the bubble eligible over a focused field.
- **If FAIL**: a prompt is skipped, or the app is not installable on Android 13 —
  ensure the 0.4.0 build (minSdk 33) is installed.

---

## Decision table

| Stage 3 | Stage 4 | Stage 5 | Stage 6 | Diagnosis |
| --- | --- | --- | --- | --- |
| FAIL (`accepted=0`) | — | — | — | `AudioCapture` never ran (capture path) |
| PASS | FAIL (no text) | FAIL (`inputTx=0`) | — | Mic captures silence, or Live ASR silent this session (retry; cross-check REST) |
| PASS | PASS | PASS | `reject=<rule>` | Transcript arrived but selector rejected it (0.3.1 trust policy should make this rare) |
| PASS | PASS | PASS | `outcome=Success` | Full pipeline works; continue the acceptance matrix |

## Acceptance matrix (after 0.3.1)

| Scenario | Runs | Expected |
| --- | --- | ---: |
| Short English, 1–3 words | 10 | ≥9 insert |
| Short opener ("Okay so…", "Yes…", "Of course…") | 10 | ≥9 insert |
| Normal English sentence | 10 | ≥9 insert |
| Long English sentence | 10 | ≥9 insert |
| Short Latin-script Hinglish | 10 | ≥9 insert, Latin script |
| Normal Latin-script Hinglish | 10 | ≥9 insert, Latin script |
| Speak immediately after tap | 10 | captured>0, insert |
| STOP immediately after speech | 10 | insert or clean retryable error |
| Cancel during connection | 5 | `outcome=Cancelled`, no insert |
| Rapid consecutive sessions | 10 | no stale-candidate errors |
| Retry button on an error | 5 | a fresh session starts |

### 0.4.0 additions

| Scenario | Runs | Expected |
| --- | --- | ---: |
| Bubble dragged position persists | 5 | bubble stays at the new position after refocus and restart |
| Tap starts from a dragged position | 5 | session starts (`SESSION DONE`) |
| Auto-stop silence (each 15/30/60/120/300 s option) | 5 | automatic `outcome=Success` at the interval |
| Auto-stop hard cap | 5 | session stops at the cap even while speaking |
| Polish None vs High distinguishable | 5 | visibly different transcript cleanliness |
| Dictionary correction applied at insertion | 5 | corrected spelling in the inserted text |
| History list / copy / delete / delete-all | 5 | all operations behave |
| Android 13 tablet first install | 3 | installs; both runtime permissions granted |
