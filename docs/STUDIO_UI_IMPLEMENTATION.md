# Studio UI — implementation checklist

Branch: **`feature/studio-ui`** (off `main`).

Visual reference: [ui-redesign-proposals.html](ui-redesign-proposals.html).

## Locked direction

- Studio dark in-app shell (default; no Dark mode toggle)
- Four tabs: Home / History / Dictionary / Settings
- Hero: words this week + 7-day sparkline
- Status: Home banner for fixable setup only; Settings → System for full gates
- History enable + retention: Settings → Privacy
- Idle bubble: unchanged
- Pill: P1 glass + P3 hairline, no timer, centered fluid soundwave
- Orientation: pager with APK Restricted settings path; human copy

## Prongs

- [x] P0 — Branch + this spec
- [x] P1 — Studio theme (`Theme.kt`, `themes.xml`, remove dark toggle)
- [x] P2 — Home (`ui/home/`, week hero, sparkline, setup banner)
- [x] P3 — Settings shell, System page, History/Dictionary polish
- [x] P4 — Orientation wizard (Restricted settings)
- [x] P5 — Pill + soundwave
- [x] P6 — Docs, CHANGELOG, tests, lint

## Do not touch

- Gemini Live model / `ModelPolicyTest`
- Dictation coordinator, warm pool, insertion, IPC
- Idle bubble / mini-dot behavior

## Version

Shipped as **1.1.0** (54).
