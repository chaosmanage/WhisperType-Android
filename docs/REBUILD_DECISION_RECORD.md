# Rebuild Decision Record (Phase 0)

This record captures the clean-room decisions made to rebuild WhisperType
per `PRD/WhisperType-Android-PRD.md` v1.0. It supersedes the runtime frozen
at tag `audit/pre-rebuild-v0.2`.

## 1. Scope decisions

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | One `app` module with strict package boundaries; no Gradle modules yet | Contracts still stabilizing (PRD §7, §16.1) |
| D2 | Persistent `TYPE_APPLICATION_OVERLAY` bubble (with `SYSTEM_ALERT_WINDOW`), Wispr-style | Wispr parity rebuild; PRD §2.2 supersedes dock geometry |
| D3 | Foreground dictation service is the sole active-session owner | PRD §16.6; the application singleton must not own mic/socket/transaction |
| D4 | Session-scoped commands and one canonical `SessionId` | PRD §8, §16.3 |
| D5 | Insertion is serialized by the accessibility service and validated immediately before commit | PRD §16.9 |
| D6 | Cleanup is idempotent and runs from `finally` on all paths | PRD §16.10 |
| D7 | History disabled by default; no transcript storage initialized while disabled | PRD §2.1, FR-10 |
| D8 | API key stored in Keystore-backed AES-GCM with random IV, app-private, backup-excluded | PRD §12 |

## 2. Reference assumptions and disposition

| # | Assumption | Evidence status | Disposition |
| --- | --- | --- | --- |
| A1 | `TYPE_APPLICATION_OVERLAY` (with `SYSTEM_ALERT_WINDOW`) reliably renders above SwiftKey's soft keyboard on Samsung Android 14+ | Unproven on physical device | Observation spike during Phase 1/2 physical gate |
| A2 | Samsung/SwiftKey expose usable editor metadata (window id, selection) for a normal text field | Unproven on physical device | Observation spike during Phase 2 |
| A3 | Foreground microphone promotion is allowed from an accessibility overlay tap context | Unproven on physical device | Observation spike during Phase 4 |
| A4 | Gemini Live `BidiGenerateContent` ordering (setup ack before audio, one activity-end) is stable | Frozen doc + prior contract tests; must revalidate | Implementation spike in workstream B; doc revalidation in `GEMINI_LIVE_PROTOCOL.md` |
| A5 | WebView/custom editors expose a usable accessibility input connection | Unproven | Out of v1 scope; treated as ineligible until proven |
| A6 | A bubble at screen edge does not obstruct application controls | Unproven on physical device | Observation spike during Phase 3 UI review |

## 3. Boundaries (PRD §16.2)

Dependency direction is one-way: UI features → application interfaces →
platform/data adapters; dictation service → audio + Gemini + target/insertion
interface; accessibility service → overlay host + target/insertion + dictation
commands; pure core ← all adapters. Cross-boundary types are the lead-owned
contracts in `com.whispertype.android.core.contracts`.

## 4. What is deliberately NOT rebuilt as a copy

No Wispr Flow source, assets, package identifiers, analytics, or secrets are
used. Assets, strings, and visuals are original (PRD §18, §2.1).
