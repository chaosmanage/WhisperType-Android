# WhisperType 0.8.0 — Latency & Style Overhaul

Step-by-step execution plan. Supersedes any earlier proposal in this repo's
history that suggested Groq Whisper for speech recognition or a non-Live Gemini
model — **both are forbidden** (see Model Policy).

---

## 0. Model policy (non-negotiable)

**Exactly one Gemini model may ever be called: the Gemini Live model
`gemini-3.1-flash-live-preview` (`BidiGenerateContent` / Live API).**

- No `generateContent`, no Flash/Pro/Flash-Lite/native-audio/TTS, no REST or
  batch Gemini variant. Only the Live model is free with the owner's API key;
  every other Gemini model would incur charges and is explicitly refused.
- **Audio goes only to Gemini Live.** Never to Groq, never to any other service.
  No Whisper, no audio upload endpoints.
- **Text-only** work (style shaping, Hinglish romanization) runs only on Groq's
  free tier.
- If a change requires a different model, it does not ship.

Enforcement layers:

1. A single pinned constant `LIVE_MODEL` in `GeminiSessionFactory.kt`; no model
   string anywhere else; no user-facing model picker.
2. `ModelPolicyTest` scans `app/src/main/java` and fails the build if any other
   `models/gemini-*` ID, any `:generateContent` URL, or any audio-upload
   endpoint appears.
3. Directive repeated at the top of `README.md`, and in `AGENTS.md`,
   `docs/ARCHITECTURE.md`, `docs/SECURITY_AND_PRIVACY.md`.

---

## 1. Target architecture

| Layer | Responsibility | Cost |
| --- | --- | --- |
| Gemini Live (pinned model) | **ASR only** — streaming `inputTranscription` while the user speaks. Echo channel off, no `systemInstruction` | free with owner's key |
| Groq `llama-3.1-8b-instant` | **Text only** — style shaping + Hinglish Devanagari→Latin romanization. Max 1 call per dictation | free tier |
| Device | Record, send, validate, insert. **No on-device text processing** | — |

Why Gemini Live stays: its ASR is *streaming*, so transcription happens during
speech. The 5–10s latency was never transcription — it was the barrier stack
built around the echo channel. Removing that stack is the fix.

---

## 2. Style calibration

| Level | Model instruction | Groq calls |
| --- | --- | --- |
| **NONE** | — inserted verbatim | 0 |
| **LOW** | Remove `um / uh / ah / er / erm` and stutter repeats, fix punctuation. Change nothing else — no rewording, no restructuring | 1 |
| **MEDIUM** | Polish grammar and word choice. **Forbidden**: reordering, merging or splitting sentences; synonym swaps; added content | 1 |
| **HIGH** | Full rewrite into clean written prose based on the original statement; never invent facts | 1 |
| **Hinglish** (any level) | Devanagari→Latin romanization **plus** that level's behaviour, in one call | 1 |

`temperature = 0` (was 0.2). Few-shot examples for LOW and MEDIUM to anchor
minimal editing.

### PolishGuard (validator, not a processor)

`core/transcript/PolishGuard.kt` — pure Kotlin, JVM-tested. Compares model
output against the raw ASR and **discards over-edited output**, inserting the
unpolished text instead.

| Level | English thresholds |
| --- | --- |
| LOW | content-word retention ≥ 85%, word count 0.8–1.15× |
| MEDIUM | retention ≥ 70%, word count 0.6–1.4× |
| HIGH | retention ≥ 40%, non-blank |

Hinglish is cross-script (Devanagari in, Latin out), so retention comparison is
meaningless: the guard instead requires predominantly-Latin output, non-blank,
length 0.5–2.0×.

This makes "MEDIUM altered my speech" structurally impossible rather than
merely discouraged by the prompt.

---

## 3. Latency targets (post-STOP → text inserted)

Budget: ASR tail + Groq text call + IPC insert.

| Path | Today | Target |
| --- | --- | --- |
| NONE | 5–10 s | **0.35–1.2 s** |
| LOW / MEDIUM / HIGH | 5–10 s | **0.55–1.7 s** |
| Hinglish | 5–10 s+ | **0.55–1.7 s** |

---

## 4. Milestones

### M0 — Policy + baseline

1. README policy block (done first, at the very top).
2. Pin the model: single `LIVE_MODEL` constant; remove any other model string;
   confirm no model picker exists in Settings.
3. Add `app/src/test/.../platform/gemini/ModelPolicyTest.kt` — source scan for
   forbidden model IDs / endpoints.
4. Repeat the directive in `AGENTS.md`, `docs/ARCHITECTURE.md`,
   `docs/SECURITY_AND_PRIVACY.md`.
5. Add an `asrTailMs` metric (`Stop` → last transcript revision) to
   `MutableSessionMetrics` and the `SESSION DONE` line.
6. Pull `SESSION DONE` from the device for 3 English + 3 Hinglish dictations to
   establish the baseline and identify which path produced the 5–10 s.

**Verify:** `:app:testDebugUnitTest` + `:app:lintDebug` green; baseline numbers
recorded in this file's appendix.

### M1 — Style recalibration

1. Rewrite `platform/groq/PolishPrompts.kt`: 4 levels × 2 languages, explicit
   prohibition lists, few-shot examples for LOW/MEDIUM.
2. `GroqTextPolisher`: `temperature = 0`; model → `llama-3.1-8b-instant`.
3. `shouldDialPolish`: NONE → no call; LOW/MEDIUM/HIGH → call; Hinglish → always.
4. New `core/transcript/PolishGuard.kt` + `PolishGuardTest` (per-level goldens,
   cross-script Hinglish case).
5. Wire the guard into the settlement path: guard rejection → insert raw ASR.

**Verify:** unit tests per level; on-device check that MEDIUM no longer
restructures speech.

### M2 — Strip echo + barrier stack (the 5–10 s fix)

Delete:

- `requestEchoFor` + `ECHO_TIMEOUT_MS` (`OkHttpGeminiLiveSession.kt`)
- `outputAudioTranscription` from the setup wire + config
- `echoAccumulator`, `echoEnabled`, echo quiet (900 ms), echo stall backstop
  (2.5 s), generation-in-flight gate, source-missing grace (2 s), hard deadline
  (20 s), `SettlePath.ECHO_*`, `isPlausibleEchoOnly`, `minEchoRatio` usage
- The second-session Hinglish repair `transliterateToLatin`
  (`FlowRuntimeService.kt:636-659`) and its host-contract method
- `LIVE_ECHO` / `AUTO` backend branching, `needsLiveEcho`,
  `liveInstructionFor`, `stableInstructionHash`, `liveInstruction` plumbing

New settlement (linear): capture quiesce → `activityEnd` → ASR quiet (~200 ms)
**or** a single `asrTailTimeoutMs` (~2.5 s) backstop → Groq call (unless NONE) →
guard → insert. Polish timeout 12 s → 4 s. Warm pool retained with a simplified
profile (no instruction hash, no echo flag).

**Verify:** rewritten coordinator settlement tests; wire test asserts no echo
config is sent; full suite + lint; device re-measure against the M0 baseline.

### M3 — Hinglish gate (owner's voice) — hard gate

1. Record real Hinglish samples on the device.
2. Compare new path (Live ASR Devanagari → Groq romanization) against today's
   echo output for accuracy and script correctness.
3. Decide `llama-3.1-8b-instant` vs `llama-3.3-70b-versatile` for romanization.

**Nothing proceeds past this milestone without owner sign-off on Hinglish
quality.**

### M4 — Micro-latency

1. Cache Groq key presence + resolved config per settings revision — removes 2–3
   main-thread file reads and an AES-GCM decrypt on main
   (`FlowRuntimeService.kt:473,485`).
2. Move Groq `provideKey` to `Dispatchers.IO`.
3. OkHttp preconnect to `api.groq.com` at dictation start so the text call has a
   hot TLS/H2 connection.
4. Tune the ASR debounce and `asrTailTimeoutMs` from measured data, not guesses.

**Verify:** device re-measure; tests + lint green.

### M5 — Docs + release

1. README architecture section; `CHANGELOG.md` entry.
2. `docs/ARCHITECTURE.md` (new pipeline), `docs/TESTING.md` (test inventory),
   `docs/SECURITY_AND_PRIVACY.md` — audio → Gemini Live only, text → Groq only,
   plus the free-tier data-use disclosure.
3. `versionCode 39`, `versionName 0.8.0` in the same commit as the behaviour
   change.
4. Signed release APK; device acceptance protocol per `docs/TESTING.md`.

---

## 5. Free-tier guardrails

- One Gemini Live session per dictation (warm-pool reuse). **Never** a second
  session — the repair path is deleted.
- At most **one** Groq text call per dictation; NONE makes zero. No retries.
- Groq `429` / quota → insert the unpolished ASR text (words are never lost).
- Groq free-tier headroom: `llama-3.1-8b-instant` = 30 RPM / 14,400 RPD /
  500K TPD. At ~330 tokens per dictation, 200 dictations/day ≈ 1.4% of daily
  requests.
- No audio upload anywhere; no Whisper; no non-Live Gemini model.

---

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| Hinglish romanization moves from the Gemini echo to Groq | M3 hard gate on owner's voice; `70b` fallback |
| Raw ASR becomes the only transcript source (echo removed) | M3 compares English accuracy against baseline |
| `asrTailTimeoutMs` becomes the single settlement backstop | Value derived from M0/M2 measurements, not guessed |
| No offline fallback if Gemini Live is unreachable | Unchanged from today; typed retry surface |
| Free-tier Gemini may use inputs to improve Google's products | Documented explicitly in `docs/SECURITY_AND_PRIVACY.md` |

---

## 7. Appendix — measurements

### M3 — live Groq validation (owner's key, 2026-08-16)

Ran the exact 0.8.0 prompts (system rules + few-shot + final user turn) against
`llama-3.1-8b-instant` and scored every reply with the `PolishGuard`
thresholds. All replies were **accepted** by the guard.

English, raw: _"um so i was thinking that we could uh maybe push the release on
friday but i i need to check with the team first"_

| Level | Latency | Guard | Output |
| --- | --- | --- | --- |
| LOW | 260 ms | accept (retention 1.00, len 0.96) | "So I was thinking that we could maybe push the release on Friday, but I need to check with the team first." |
| MEDIUM | 124 ms | accept (0.96 / 0.91) | "I was thinking that we could maybe push the release on Friday, but I need to check with the team first." |
| HIGH | 144 ms | accept (0.70 / 0.78) | "I was thinking of pushing the release on Friday, but I need to confirm with the team first." |

Hinglish, raw: _"मैं आज ऑफिस नहीं जा रहा हूँ um क्योंकि मेरी तबीयत ठीक नहीं है इसलिए मैंने manager को message कर दिया"_

| Level | Latency | Output |
| --- | --- | --- |
| LOW | 216 ms | "Main aaj office nahin ja raha hoon kyonki meri tabiyat theek nahin hai, isliye maine manager ko message kar diya." |
| MEDIUM | 153 ms | same as LOW plus comma placement |
| HIGH | 167 ms | "Aaj office nahi ja raha hoon kyonki meri tabiyat theek nahi hai, isliye maine manager ko message kar diya hai." |

Second Hinglish sample romanized correctly too, preserving `plan`, `cancel`,
`urgent`, `busy` as English.

**Conclusions:** `llama-3.1-8b-instant` is sufficient for both languages —
LOW preserves every word, MEDIUM corrects without restructuring, HIGH rewrites,
and Hinglish romanization is colloquial and loanword-safe. Text-stage latency is
**124-260 ms**, so the 4 s dial budget is generous.

**Free-tier finding:** the binding limit is **tokens per minute, per model**
(`x-ratelimit-limit-tokens: 6000` for the 8B model), not requests
(`14,400/day`). Each dictation costs ~400-590 tokens, i.e. ~10-15 dictations per
minute before a 429 — far above human dictation rate, but it is now handled: a
429 is retried once on `llama-3.3-70b-versatile`, which has its own 12,000 TPM
budget.

### Device baseline (pending)

To be filled from `SESSION DONE` lines on the owner's device for 3 English + 3
Hinglish dictations, comparing `stopToLastInputRevision`, `polishDurationMs` and
`stopToInsertionResult` against the 0.7.0 build.

| # | Language | Level | stopToLastInputRevision | polishDurationMs | total post-stop |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

---

## 8. Follow-ups (not blocking 0.8.0)

- `TranscriptCompleteness.covers` and `isPlausibleForDuration` are now unused by
  production code (they served the deleted echo-completeness gate). They remain
  covered by `TranscriptCompletenessTest`; delete in a dedicated cleanup change
  rather than widening this diff.
- `SettlePath.ECHO_*` / `SettlementReason.ECHO_DEBOUNCE` / `HARD_DEADLINE` enum
  values are inert diagnostic vocabulary; prune when the metrics summary is next
  revised.
- The Gemini wire parser still reads `outputTranscription` defensively even
  though the setup never enables it.
