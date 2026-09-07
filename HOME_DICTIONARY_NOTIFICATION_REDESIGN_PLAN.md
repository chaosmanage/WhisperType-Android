# Home, dictionary, and notification redesign plan

## Confirmed diagnosis

- The oversized Recent card and undersized statistics are caused by `weight(1f)` in `HomeScreen`: the Hero and Recent cards each consume half of the remaining viewport, so a one-item Recent list becomes mostly empty space.
- The three equal-width metric cards force “Words / session” into an accidental wrap at phone widths.
- Dictionary does not copy the first field into the second. It accepts a blank replacement, then displays it as the source through `entry.replace.ifBlank { entry.match }`; therefore `test → test` means the replacement was saved blank and is a no-op.
- “WhisperType is listening” is displayed whenever the foreground overlay service runs, despite microphone capture beginning only after the bubble is tapped.

## Locked visual direction

Keep the dark Studio theme, warm white text, muted stone secondary text, and mint accent. Recompose Home as an intentional daily dashboard rather than a set of stretched cards.

- Screen gutter: 20dp horizontal; 16dp after system inset; 24dp before bottom navigation.
- Vertical rhythm: 8dp inside components, 12dp between related components, 20dp between sections.
- Surfaces: `StudioColors.Surface`, one hairline border, no shadow.
- Radii: 24dp weekly hero; 18dp metrics, Recent, and dictionary rows; 14dp fields and buttons.
- Interactive targets: at least 48dp.
- Screens scroll; content cards use intrinsic content height and never fill arbitrary viewport space.

## 1. Home screen: exact design

Files: `app/src/main/java/com/whispertype/android/ui/home/HomeScreen.kt` and `app/src/main/java/com/whispertype/android/ui/home/HomeComponents.kt`.

### Header

- Date: 14sp muted text, for example “Monday 7 September”.
- Greeting: 30sp semibold immediately below, for example “Good night”.
- Use 20dp from greeting to hero.

### Setup banner

- Display only for actual setup blockers, never ordinary contexts such as “no text field focused”.
- Full width, 52dp minimum height, one clear sentence, right-aligned Fix action.

### Weekly hero

- Full width and 204dp high, expanding only when needed for accessibility text scaling.
- `WORDS THIS WEEK` label in 13sp uppercase muted text.
- Weekly word count in 64sp mint.
- Context line such as `1 session · 124 words/min`, with correct pluralization; otherwise “No sessions yet this week”.
- Replace the diagonal connected sparkline with seven independent rounded vertical bars aligned to a common baseline:
  - current day: mint;
  - earlier active days: muted mint;
  - zero days: dim hairline bars;
  - weekday initials below: `M T W T F S S`.
- Expose a semantics description, for example “5 words this week, recorded on Monday”.

### At a glance

- 20dp below the hero, show `AT A GLANCE` in uppercase 13sp muted text.
- Replace the three-item row with this two-column grid:
  - first row: Sessions | Words / min;
  - second row: Words / session, spanning both columns.
- First-row cards: 88dp minimum; spanning card: 76dp minimum.
- Values: top-left, 26sp semibold with tabular figures.
- Labels: bottom-left, 13sp single line:
  - All-time sessions;
  - Average words / min;
  - Average words / session.
- If width cannot accommodate two 132dp cards with standard gutters, convert all three to one column. Never reduce typography or padding to force a two-column grid.
- For unavailable WPM, render `—` and “No timed sessions yet”.

### Recent

- 24dp below metrics, show `RECENT` in 13sp uppercase muted text.
- Content-height card only:
  - one item: about 112dp;
  - two items: about 168dp with hairline divider;
  - no `weight()` or `fillMaxHeight()`.
- Each item: two-line maximum transcript at 16sp, then `Today · 10:24 pm · Inserted` at 13sp muted.
- A subtle “View history” action opens the existing History tab.
- With history on and zero entries: a 120dp card with app mark, “Your first dictation will appear here”, and “Tap the bubble in any text field to begin.”
- With history off: replace analytics/Recent with a 176dp privacy card explaining that nothing is stored and providing an outlined “Turn on history” link to the current privacy setting.

### Home implementation

- Replace weighted layout with `verticalScroll(rememberScrollState())`.
- Extract testable helpers for metric presentation, weekly bars, and recent-entry metadata.
- Add `onOpenHistory` and `onEnableHistory` callbacks from `MainActivity`.
- Preserve local-only encrypted history and never log transcript content.

## 2. Dictionary: exact design and interaction

Files: `app/src/main/java/com/whispertype/android/ui/dictionary/DictionaryScreen.kt`, `app/src/main/java/com/whispertype/android/data/settings/SettingsRepository.kt`, and dictionary strings.

### Header

- Title: `Dictionary`.
- Description: “Teach WhisperType how to write names, terms, and phrases after it transcribes them.”
- Example callout: `You say: “kuber net ease” → It writes: “Kubernetes”.`
- Do not call the target “optional”.

### Saved rules

- 18dp-radius cards, 64dp minimum content height.
- Two explicit labeled lines:
  - `YOU SAY` (12sp uppercase muted) then source (16sp);
  - `IT WRITES` (12sp uppercase muted) then replacement (16sp mint).
- Separate 48dp Edit and Delete icon buttons with accessibility labels.
- Legacy blank target shows “Needs a replacement” in error text; never render it as a valid identical pair.
- Edit opens a prefilled version of the editor; Clear all requires confirmation and states the number of rules removed.

### Add/edit sheet

- Filled 52dp mint `Add rule` button below the list.
- Modal bottom sheet with `Add dictionary rule` or `Edit dictionary rule`.
- Required fields:
  - `What you say`, placeholder `e.g. kuber net ease`;
  - `How it should be written`, placeholder `e.g. Kubernetes`.
- Persistent labels, 56dp fields, IME Next from first to second; IME Done only saves a valid rule.
- Live preview: `“kuber net ease” will be written as “Kubernetes”`.
- Save disabled until both trimmed values exist.
- For case-insensitive identical text, show “This rule will not change the transcript” and require explicit `Save anyway`; revision remains default.
- Trim outer whitespace only. Preserve capitalization and internal spaces. Support multiword source/replacement.

### Dictionary correctness

- Keep existing JSON readable; do not silently rewrite legacy blank replacement to source.
- Add an explicit update API (by original match or stable ID) so edits reliably replace saved entries.
- Keep correction exactly once at final-text-before-insertion in `FlowRuntimeService`.
- Make blank legacy replacements no-ops in `DictionaryCorrections`, never deletions.

## 3. Notification: exact product behavior

The persistent notification must remain while the bubble is available. The bubble’s `FlowRuntimeService` is a foreground service; Android requires its notification. Removing it would make the overlay unreliable and vulnerable to background termination.

### States

- Bubble ready, microphone off:
  - Channel: `WhisperType bubble`;
  - title: `WhisperType bubble is ready`;
  - body: `Your microphone turns on only when you tap the bubble.`;
  - low priority, silent, ongoing.
- Audio capture active:
  - title: `Recording dictation`;
  - body: `WhisperType is using your microphone.`;
  - show Stop/Cancel only if the actions are wired to `DictationCoordinator`.
- Finalizing/inserting:
  - title: `Finishing dictation`;
  - body: `Your microphone is off.`
- App enabled kill switch off:
  - remove notification, overlay, microphone capture, and active session.
- Accessibility service disconnected:
  - retain the existing separate, actionable accessibility fault notification.

### Permission policy

- `POST_NOTIFICATIONS` remains declared and is requested with an optional rationale.
- Declining notifications must not be a Home/System setup failure or prevent dictation.
- Explain that Android may still show an active foreground service in Task Manager.
- Add a non-blocking Settings row that opens Android notification settings.
- Update notification on each state transition and return FGS type from `specialUse|microphone` to `specialUse` immediately after capture ends.

## 4. Implementation order

1. Add shared layout constants in `ui/theme/Theme.kt`.
2. Build Home hierarchy, weekly bars, adaptive metric grid, compact Recent card, empty states, and callbacks.
3. Add Home previews/helpers tests for no history, zero/one/two entries, high counts, missing duration, narrow screens, and large font.
4. Replace Dictionary list and create validated Add/Edit bottom sheet.
5. Add repository/core handling for editing and legacy blank replacements; write persistence and correction regression tests.
6. Implement state-specific foreground notification copy and make notification permission non-blocking.
7. Update onboarding, Settings/System diagnostics, `docs/USER_SETUP.md`, `docs/TROUBLESHOOTING.md`, and `docs/TESTING.md`.
8. Update `CHANGELOG.md`; increment version code by exactly one and patch `versionName` in `app/build.gradle.kts`.

## 5. Acceptance criteria

Automated:

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew :app:assembleDebug`
- Dictionary engine tests: boundaries, punctuation, casing, multiword/overlapping rules, identical rules, legacy blanks.
- Persistence tests: create, edit, duplicate source replacement, delete, clear, legacy JSON round-trip.

Manual Android:

- At 360dp width, no Home text clips; metric grid is `2 + 1`; one Recent entry has no dead vertical space.
- At 200% font scale and landscape, content scrolls without overlap.
- Add `kuber net ease → Kubernetes`; dictate the source; focused editor receives `Kubernetes`. Confirm multiword and casing rules.
- An incomplete rule cannot save; the old `test → test` rule is editable/removable.
- Idle notification says microphone is off. Start/stop changes copy correctly. Deny notifications, background the app, and confirm bubble/dictation remain stable.
- Complete existing physical-device overlay, insertion, kill-switch, and notification recovery checks before release.
