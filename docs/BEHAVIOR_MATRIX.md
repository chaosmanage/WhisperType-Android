# WhisperType Behavior Matrix (Phase 0)

Reference: PRD §19.2. Reference evidence = the frozen `com.wispr.flowapp`
APKM (tracked at repository root) and the observed interaction model. The
superseded WhisperType runtime is frozen at tag `audit/pre-rebuild-v0.2`
and is not used as an implementation source.

Each row records a reference behavior, the evidence it was derived from,
the resulting WhisperType requirement, the implementing workstream, the
test that proves it, and any intentional difference.

| Reference behavior | Evidence | WhisperType requirement | Implementation owner | Test | Difference / rationale |
| --- | --- | --- | --- | --- | --- |
| Persistent small floating bubble | Reference APK analysis + observed model | Persistent touchable `TYPE_APPLICATION_OVERLAY` bubble (Wispr parity) | platform-overlay | Overlay host + physical smoke | Original assets/branding, no keyboard-bound dock geometry |
| Bubble eligibility | Reference observation | Focused supported editor + visible soft keyboard + non-secure + service connected + mic + key | platform-accessibility | Eligibility matrix | Fail closed on uncertainty |
| Bubble is small, edge-positioned | Reference observation | Small bubble, >=48dp target, edge-anchored | platform-overlay | Compose UI tests + bounds tests | Original geometry derived from density, not copied pixels |
| Bubble tap starts dictation | Reference observation | Explicit tap captures immutable target, starts foreground mic service | platform-accessibility + dictation-service | Target capture tests + FGS startup | Session-scoped commands, no hidden listening |
| Recording panel with waveform | Reference observation | Panel with responsive waveform, Stop/Cancel, no transcript text | platform-overlay | Compose UI tests | No copied artwork, static waveform under reduced motion |
| Text insertion into focused field | Reference APK analysis | Validated input-method commit transaction | platform-accessibility | Editor host tests + physical smoke | Current official accessibility input connection surface |
| Insertion recovery / fallback | Reference APK analysis | Typed failure + explicit Copy fallback, never blind retry | platform-accessibility + dictation-service | Insertion failure suite | Never duplicate text, Copy only after explicit tap |
| Service/watchdog recovery | Reference APK analysis | Visible recovery path, typed diagnostics, reconnect | platform-overlay + feature-onboarding | Kill/re-enable tests | Original diagnostics |
| Keyboard stays selected | Reference observation | WhisperType is not an IME; never switches keyboards | platform-accessibility | Keyboard-preservation smoke | Matches v1 non-goal |
| Secure-field guard | Reference observation + platform requirements | Never show/start in password/PIN/payment/auth/private fields | platform-accessibility | Secure-field suite | Fail closed |
| Privacy disclosure | Platform requirement | Accessibility description accurately describes field detection, overlay, insertion | dictation-service + lead | Manifest + strings audit | Original disclosure text |

## Unresolved reference assumptions

See `docs/REBUILD_DECISION_RECORD.md`. Items marked *spike* require a
physical-device experiment before implementation is considered proven.
