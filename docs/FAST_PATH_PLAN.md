# Fast Path Plan (0.7.0)

Status: **planned, not implemented.** Supersedes the latency sections of
`OPTIMIZATION_PLAN.md`. No physical device is available, so every latency claim here
ships **device-pending** until read off a real `SESSION DONE` line.

## 1. Problem

The inserted text is the model's *spoken reply* (`outputTranscription`). A native
audio Live model must synthesize audio for every word, so finalization scales with
speaking time — **~0.6 s/sentence, ~3 s/30 words, ~8 s/100 words**
(`OPTIMIZATION_PLAN.md:23-24`).

Raw ASR (`inputTranscription`) is complete **~0.5 s after `activityEnd`, at any
length** (`OPTIMIZATION_PLAN.md:25-26`) and is discarded.

The ASR is not the bottleneck. `polish=NONE/LOW` already disables the echo and settles
in **~0.7 s flat** on the same socket (`GEMINI_LIVE.md:13`) — ~10x faster, same model,
same network.

Hinglish is the worst case and it is the default (`speech_mode` defaults to `HINGLISH`,
`polish_level` to `MEDIUM`). The echo (~8 s) may be followed by a Devanagari-to-Latin
repair that **opens an entire second Live session to transliterate text**
(`FlowRuntimeService.kt:576`), measured **0.5-1.9 s** including a ~700 ms cold connect
(`LONG_DICTATION_FIX_PLAN.md:47,60-64,168`).

Root cause, stated plainly: **the echo is simultaneously the transcript, the polish
engine, and the script normalizer.** All three are bound to audio synthesis time.

## 2. Constraints (user-mandated, this revision)

| Constraint | Consequence |
|---|---|
| Gemini: `gemini-3.1-flash-live-preview` only | Live model becomes **ASR transport only**. No Flash, no Flash-Lite. |
| No on-device LLM | Rules out Gemma 270M / Qwen3 0.6B polish |
| Must run on low-spec devices | Rules out local ASR, model downloads, APK growth |
| Must be free | User-supplied keys on free tiers only |

**Structural consequence.** The Live model is audio-output-only; TEXT modality is
unavailable (`GEMINI_LIVE.md:48-49`). Polished text requires the model to *speak*.
**Fast polish is therefore impossible within Gemini.** A free non-Gemini text LLM is
not a preference — it is structurally required.

**Second structural consequence.** `inputAudioTranscription` is an empty protobuf
`{}`; a `languageCode` field was tried and the API rejects it verbatim with
`"unknown name language code"` (`GeminiLiveWire.kt:62-68`, `GEMINI_LIVE.md:688-693`).
Script and language bias can only travel in `systemInstruction`, which affects only the
echo (`LanguageMode.kt:83-94`). So **raw ASR gives Devanagari for Hindi and there is no
way to ask it not to.**

### Mandate overrides

Explicitly reversed by user instruction, 2026-08-15:

- `OPTIMIZATION_PLAN.md:7-9` — "no other model, no other provider, no new API key"
- `LONG_DICTATION_FIX_PLAN.md:93` — "MEDIUM/HIGH keep waiting for the polished echo"
- `CHANGELOG.md:200-202,219-222` — live-model-only enforcement (0.5.0/0.5.1)

Retained: `gemini-3.1-flash-live-preview` remains the only Gemini model
(`GeminiSessionFactory.kt:16`).

## 3. Target architecture

```
mic -> 16k PCM -> Gemini Live  (inputTranscription only)      ~0.5s after stop
                       |  raw text, any script
                       v
                 TextPolisher  (one remote call)              ~0.8s
                       |  on failure/timeout/no-key -> raw text
                       v
                 IPC -> accessibility -> commitText
```

The three jobs the echo conflated are now separate: **Live = transcript**,
**TextPolisher = polish + script normalization**, **raw text = the floor**.

A remote text call is the most low-spec-friendly option available: **zero APK growth,
zero RAM, zero CPU.** The low-spec constraint argues *for* this and against every local
alternative.

## 4. Provider: Groq

Verified 2026-08-15 against
`console.groq.com/docs/{rate-limits,billing-faqs,deprecations,legal/services-agreement}`.

- **No credit card.** Email signup only. Permanent free tier, not expiring credits.
- `openai/gpt-oss-20b` and `qwen/qwen3.6-27b`:
  **30 RPM / 1,000 RPD / 8,000 TPM / 200,000 TPD**
- ~0.8 s TTFT, ~940-1,000 t/s — fastest no-card provider available
- ToS 2.1 **explicitly permits** making the service available to End Users through a
  customer application; each user's own key means each user is the Customer
- ToS 4.2: **does not train on inputs.** Decisive for a dictation app.
- ToS 2.2 prohibits selling/transferring keys — the app must only accept a user-pasted
  key, never aggregate or ship one.

> **Warning:** `llama-3.1-8b-instant` and `llama-3.3-70b-versatile` shut down
> **2026-08-16**. Do not use. `openai/gpt-oss-20b` is the official successor.

### Model routing

| Mode | Model | Rationale |
|---|---|---|
| Hinglish | `qwen/qwen3.6-27b` | Qwen3 is the only open family that holds up on romanized Hindi (Indi-RomCoM, arXiv 2606.30790) |
| English | `openai/gpt-oss-20b` | Groq's recommended stable model; faster |

Same key, same endpoint. Model IDs live in named constants; checking Groq's
deprecations page is a release-checklist item.

### Quota

TPD binds, not RPD. Estimated ~600 tokens per call:

| Dictation | Est. tokens | Ceiling/day |
|---|---|---|
| 50 words, English | ~450 | ~440 |
| 50 words, Hinglish | ~660 | ~300 |
| 200 words, Hinglish | ~1,600 | **~125** |

**~125-440 dictations/day.** TPM 8,000 allows ~5-13/minute; sustained human dictation
is ~4-6/minute, so TPM is adequate but not lavish.

Devanagari tokenizes at ~2-4 tokens/word vs ~1.3 for English, so the default mode is
the most quota-hungry. Groq documents that cached tokens do not count toward limits —
the system prompt is byte-identical every call, so caching should erase the dominant
fixed cost. **Unverified; measure it.**

**No provider offers unlimited free usage.** Verified 2026-08-15. Mistral's Experiment
tier is the largest recurring quota (~1B tok/mo, no card, SMS verify) but is capped at
**2 RPM**, **trains on data by default**, and ranks *behind both IndicXlit and GPT-4o*
on Indian-language transliteration (arXiv 2505.19851) — weak at exactly the hardest
task here. It is documented as optional English-only overflow, not the default. See
Appendix A.

**Quota is not a blocker by design:** exhaustion degrades to raw text at ~0.7 s, still
~10x faster than today.

## 5. Polish backend setting

New `polish_backend` DataStore key, enum `PolishBackend`:

| Value | Behavior | stop-to-insert |
|---|---|---|
| `AUTO` (default) | Groq if key present; else `LIVE_ECHO` for Hinglish, `NONE` for English | — |
| `GROQ` | Raw ASR + one Groq call | **~1.2 s** |
| `NONE` | Raw ASR inserted as-is | **~0.7 s** |
| `LIVE_ECHO` | Current 0.6.2 behavior, unchanged | ~8-10 s |

`AUTO` guarantees no regression for users who never add a Groq key.

The Groq key is stored in the existing Keystore/AES-GCM secret store, mirroring the
Gemini key path (`data/secrets/`). Never logged, never in a URL.

## 6. Stage 1 — work items

| # | Change | Files | Verify |
|---|---|---|---|
| 1 | `TextPolisher` contract: `suspend fun polish(text, language, style): PolishResult` | `core/contracts/TextPolisher.kt` (new) | framework-free, JVM-testable |
| 2 | Groq impl on `sharedOkHttpClient`, OpenAI-compatible `/openai/v1/chat/completions` | `platform/polish/GroqTextPolisher.kt` (new) | MockWebServer, **never a live key** |
| 3 | Prompt builder: polish level + Hinglish romanization in one prompt | `platform/polish/PolishPrompts.kt` (new) | golden-output tests |
| 4 | `PolishBackend` enum, `polish_backend` key, Groq secret storage | `core/model/`, `data/settings/SettingsRepository.kt`, `data/secrets/` | round-trip tests |
| 5 | Backend != `LIVE_ECHO` implies `outputAudioTranscription = false` and no `systemInstruction` | `GeminiSessionConfig.kt`, `GeminiLiveWire.kt` | `GeminiLiveWireTest` asserts both absent |
| 6 | Settle on raw; quiet window = `settleDebounceMs` (250 ms) always | `DictationCoordinator.kt:966-1044` | `stopToSettled` ~500-700 ms, length-independent |
| 7 | Polish stage between settle and insert. 3 s timeout, cancellable on new dictation | `DictationCoordinator.kt:1155` | timeout test inserts **raw** |
| 8 | Metrics: `PolishRequested`/`PolishCompleted` marks; `polishDurationMs`, `polishOutcome`, `promptTokens`, `totalTokens` | `core/model/MutableSessionMetrics.kt`, `DictationMetrics.kt` | integers + typed codes only, no text |
| 9 | Settings UI: backend picker + Groq key field | `ui/` | — |

**Safety invariant: polish failure never loses text.** Timeout, 429, 5xx, network
error, empty response, or missing key all insert raw and record a typed outcome code.
Polish is an enhancement layer, never a dependency.

Implementation note: the REST `:generateContent` client deleted in 0.5.0
(`CHANGELOG.md:200-202`) is recoverable from git history — reusable HTTP plumbing.

## 7. Stage 2 — deferred deletion

Gated on Stage 1 device validation and proven Groq romanization quality.

Removing `LIVE_ECHO` deletes ~2,500 lines: `settleHinglish`
(`DictationCoordinator.kt:1072-1125`), `SettlePath` (all 5 variants),
`isCompleteHinglishEcho`, `transliterateToLatin` + `TRANSLITERATION_INSTRUCTION`
(`FlowRuntimeService.kt:576,793-797`), `requestEchoFor`
(`OkHttpGeminiLiveSession.kt:210-244`), `TranscriptSource.ECHO`,
`LanguageMode.liveInstruction`, echo-coverage in `TranscriptCompleteness`,
`echoQuietMs`/`echoStallMs`/`echoFallbackWaitMs`, and ~15 echo metrics fields.

**Stage 1 keeps all of it.** Net line reduction in Stage 1 is small — that is the
honest, accepted cost of a no-regression fallback.

## 8. Explicitly not doing

| Rejected | Reason |
|---|---|
| Streaming / sentence-level polish | Groq free tier is **8,000 TPM**; multiple calls per dictation with growing context would exhaust it. One call at settle. |
| Local ASR (Parakeet, Whisper, sherpa-onnx, Vosk) | Low-spec constraint. Parakeet TDT is 460 MB int8 / ~1.2 GB RAM and **has no Hindi** (25 European languages). Every Hindi-capable local model emits Devanagari. |
| On-device punctuation model (7 MB) | English-only — no benefit for the Hinglish default, and adds ~20 MB APK for the sherpa-onnx runtime. |
| On-device LLM polish | Excluded by constraint; also 0.6-3.5 s and ~500 MB. |
| On-device Devanagari romanization | Pure-JVM options give academic IAST (`kya kar rahe ho` with diacritics); the natural-colloquial one is AGPL-3.0; IndicXlit is MIT but has no public Indic-to-Roman ONNX export. |
| NVIDIA NIM | Trial ToS forbids production use. |
| OpenRouter free | ~1.5-2 s (slower than the incumbent) and 50 RPD without a $10 purchase. |
| Rewriting the app | The two-process split, `RuntimeIpc`, insertion/verification, overlay, accessibility, audio capture, settings, and history are orthogonal to ASR and working. The ASR seam is already clean (`core/contracts/`, one chokepoint at `FlowRuntimeService.resolveSession`). A rewrite is risk without latency benefit. |

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Groq Hinglish romanization quality unverified** — no public benchmark; frontier models still make schwa/nasalization errors (EACL 2026 Findings 291) | **High** | ~30-sentence eval fixtures: schwa deletion, nasalization, English loanwords. **Manual dev-time gate — cannot run in CI (needs a key).** |
| Unknown whether raw ASR already punctuates English | Medium | Device probe. **If yes, English skips the polish call entirely** and the plan shrinks. |
| `CHANGELOG.md:206` asserts "raw ASR = Devanagari" but probe data is not in the repo and transcripts are never logged | Low | The polish prompt handles Devanagari, Latin, or mixed input — **the design is immune to the answer.** |
| Groq model churn (two models die 2026-08-16) | Medium | Model IDs as constants; deprecations page on the release checklist. |
| Free-tier quota exhaustion | Low | Typed error inserts raw; surface once in UI. |
| Raw ASR occasionally never delivered (`GEMINI_LIVE.md:505`, 82.8 s lecture) | Low | Keep the 20 s hard deadline; empty raw is a typed error, never a silent drop. |
| Second key means onboarding friction | Low | Optional. `AUTO` degrades to current behavior. |

## 10. Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug          # warningsAsErrors + allWarningsAsErrors
./gradlew :app:assembleDebug
```

Unit coverage: Groq client via MockWebServer (never a live key, per `AGENTS.md`), prompt
golden outputs, timeout/429/5xx falling back to raw, cancellation on new dictation,
settle-on-raw in the coordinator, wire assertions for absent `systemInstruction` and
`outputAudioTranscription`.

Device gate per `docs/TESTING.md` — read
`SESSION DONE stopToSettled= polishDurationMs= polishOutcome= totalTokens=` across
{20, 50, 100, 200 words} x {English, Hinglish} x {NONE, MEDIUM, HIGH} x
{GROQ, NONE, LIVE_ECHO}.

**No latency claim in this document is real until read off a device.** The token
figures in section 4 are estimates; work item 8 replaces them with measurements.

## 11. Expected outcome

| | 0.6.2 | 0.7.0 |
|---|---|---|
| English, 100 words, MEDIUM | ~8 s | **~1.2 s** |
| Hinglish, 100 words, MEDIUM | ~8-10 s | **~1.2 s** |
| Either, NONE | ~0.7 s | ~0.7 s |
| Quota exhausted / no key / polish offline | — | ~0.7 s (raw) |
| APK size | 3.6 MB | **3.6 MB** |
| RAM / CPU | — | **unchanged** |

## 12. Housekeeping

`versionCode 37 -> 38`, `versionName 0.6.2 -> 0.7.0` (`app/build.gradle.kts:40-41`),
CHANGELOG entry, and same-commit doc updates:

- `docs/ARCHITECTURE.md` — Live session is now ASR-only; new `platform/polish/` layer
- `docs/GEMINI_LIVE.md` — echo path becomes one backend among four
- `docs/USER_SETUP.md:133` and `README.md:41-43` — the "guaranteed Latin script via
  echo-only" claims go stale
- `docs/TESTING.md` — new acceptance matrix rows for the polish backends

## Appendix A — free provider survey (verified 2026-08-15)

No provider offers unlimited free text inference. Largest recurring quotas:

| Provider | Card | Quota | Dictations/day | Trains | Notes |
|---|---|---|---|---|---|
| Mistral Experiment | No (SMS) | ~1B tok/mo | ~2,880 (2 RPM cap) | **Yes**, opt-out | Weak Indic transliteration |
| **Groq** | **No** | 200K TPD | **~125-440** | **No** | **Chosen.** Fastest no-card. |
| Google AI Studio | No | 1,500 RPD | ~1,500 | Yes | Excluded by constraint |
| Cloudflare Workers AI | No | 10K neurons/day | ~130-330 | No | Free roster shrinking (2026-07-28) |
| SambaNova | No | 20 RPD/model | ~20 | Unclear | — |
| OpenRouter free | No | 50/day (1,000 with $10) | ~50 | Varies | ~1.5-2 s |
| IBM watsonx Lite | Yes | 300K tok/mo | ~17 | No | Slow |

Not viable: **Cerebras** (card required, $5/30-day credits), **GitHub Models** (retired
2026-07-30), **xAI** (free credits withdrawn), **Moonshot/Kimi** (no free tier),
**DeepSeek / Alibaba / Baidu** (30-90 day trials; mainland data processing, a privacy
flag for transcripts), **Together / Fireworks / DeepInfra / Nebius / Chutes /
Featherless / Hyperbolic** (one-time credits or paid).

## Appendix B — on-device options surveyed and rejected

Retained so this research is not repeated.

| Option | Size (int8) | Streaming | Punct+case | Hindi | Verdict |
|---|---|---|---|---|---|
| Parakeet TDT 0.6B | 460 MB, ~1.2 GB RAM | No (offline) | Yes | **No** (25 EU langs) | Too big; no Hindi |
| Parakeet-Unified / Nemotron | ~630 MB | Yes | Yes | No | Too big; NVIDIA Open Model License |
| sherpa-onnx streaming Zipformer en | ~71 MB | Yes | No | No | LibriSpeech-only; generalizes poorly |
| Moonshine tiny/base | ~118 / 272 MB | Simulated | Yes | No | English/Spanish only |
| whisper.cpp tiny/base | 31 / 57 MB | No partials | Partial | Devanagari | ~30% WER on conversational Hinglish at large-v3 |
| Vosk small-hi | 42 MB | Yes | No | Devanagari | WER 20.9; no romanization |
| Android on-device `SpeechRecognizer` | 0 MB | Yes | Yes (API 33+) | `hi-IN` gives Devanagari | No Latin mode; OEM-unreliable; competes for the mic |
| sherpa-onnx online punct | 7.1 MB | Yes | Yes | **English only** | No value for the Hinglish default |

**Conclusion: no free on-device ASR emits Latin-script Hinglish.** Devanagari ASR plus
a romanizer is the only local path, and the romanizer is either academic quality, AGPL,
or requires a DIY ONNX export. Revisit only as an opt-in English-offline tier.
