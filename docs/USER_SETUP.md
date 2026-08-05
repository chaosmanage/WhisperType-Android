## WhisperType Android setup

WhisperType Android keeps your existing SwiftKey, Gboard, or Samsung Keyboard active. It does not replace your keyboard.

When you focus a normal text field, a small WhisperType microphone control appears attached to the keyboard boundary. Tap it to dictate. The keyboard is visually replaced by a WhisperType voice panel while you speak, then returns after the text is inserted.

## Requirements

- Android 13 or newer (0.4.0 targets Android 13+ with a runtime RECORD_AUDIO + POST_NOTIFICATIONS permission flow).
- A supported Pixel or Samsung device, or an Android 13+ tablet.
- Gboard, SwiftKey, or Samsung Keyboard in standard docked mode.
- Internet access for Gemini Live.
- A Gemini API key.
- Permission to use the microphone.
- WhisperType Accessibility Service enabled.

## Install the APK

1. Copy the signed WhisperType APK to your phone.
2. Open the APK.
3. If Android blocks installation, open the displayed settings page and allow the application used to open the APK to install unknown apps.
4. Return to the APK and install it.
5. Open WhisperType.

For development installation from a computer:

```powershell
adb install -r WhisperType-Android-0.1.0-release.apk
```

## Grant microphone permission

1. Open WhisperType.
2. Tap `Allow microphone`.
3. Choose `While using the app` if Android presents that option.
4. Return to WhisperType.
5. Confirm that microphone status says `Ready`.

WhisperType does not record continuously. The microphone is used only during an active dictation session.

## Grant notification permission

WhisperType shows a recording notification while the microphone is active.

1. When Android asks for notifications, tap `Allow`.
2. If you previously denied it, open:

   `Settings → Apps → WhisperType → Notifications`

3. Enable notifications.

The notification includes Stop and Cancel actions while recording.

## Enable Accessibility access

Accessibility access is required so WhisperType can:

- detect when a text field is active,
- detect the active keyboard’s position,
- place the docked control next to the keyboard,
- insert the final text at the cursor.

WhisperType does not use this permission to read or store unrelated screen content.

### Pixel / stock Android

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Downloaded apps` or `Installed apps`.
4. Tap `WhisperType`.
5. Turn on `WhisperType`.
6. Read the disclosure.
7. Tap `Allow`.
8. Return to WhisperType.

### Samsung Galaxy

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Installed apps`.
4. Tap `WhisperType`.
5. Turn on the service.
6. Confirm the disclosure.
7. Return to WhisperType.

The exact labels may vary slightly by Android or One UI version.

## Keep your normal keyboard selected

WhisperType does not need to become your default keyboard.

To verify your keyboard:

### Pixel / stock Android

`Settings → System → Keyboard → On-screen keyboard`

### Samsung

`Settings → General management → Keyboard list and default`

Keep SwiftKey, Gboard, or Samsung Keyboard selected as your default.

## Configure the Gemini API key

1. Open WhisperType.
2. Open `Settings`.
3. Tap `Gemini API key`.
4. Paste or type your key.
5. Tap `Save`.
6. Tap `Test connection`.
7. Confirm that the test succeeds.

The key is encrypted with Android Keystore-backed storage. It is not saved in normal app preferences, history, logs, backups, or the clipboard.

## Choose speech mode

Open:

`WhisperType → Settings → Speech mode`

Choose:

- `English`
- `Hinglish`

Hinglish supports natural English/Hindi code-switching but always produces Latin-script output. Devanagari is rejected.

## Customize the dock

Open:

`WhisperType → Settings → Dock`

Available controls:

- Position: Left, Center, Right.
- Size: Compact, Standard, Large.
- Vertical overlap: Mostly over keyboard or Mostly above keyboard.
- Opacity.
- Theme: System, Light, Dark.
- Accent color.
- Haptic feedback.
- Optional start/stop sound.
- Elapsed-time visibility.
- Cancel button side.

The mic bubble is freely draggable: drag it with your finger to place it anywhere on screen. Its position persists across sessions and device restarts. To restore the default position, use `WhisperType → Settings → Reset bubble position`.

## Set the auto-stop timeout

Open:

`WhisperType → Settings → Auto-stop timeout`

Choose 15, 30, 60, 120, or 300 seconds. The default is 60.

Recording stops automatically after you have been silent for the chosen interval. A hard cap also stops any session that reaches the maximum duration, even if you are still speaking.

## Choose the output polish

Open:

`WhisperType → Settings → Output polish`

Choose None, Low, Medium, or High. The default is Medium.

Higher polish levels ask the transcription engine to clean up filler words, disfluencies, and speech quirks; None keeps the raw transcript. The level is passed to the Gemini session as a system instruction (see `docs/GEMINI_LIVE_TRANSCRIPTION.md`).

## Set up the custom dictionary

Open:

`WhisperType → Settings → Custom dictionary`

- Tap `Add word` and enter a word or phrase.
- Optionally enter `Always write as` to force a specific spelling.
- Delete a single entry, or `Clear all` to remove every entry.

Corrections are applied when the transcript is inserted, so the dictionary never rewrites the live transcript mid-session. Matching is word-boundary and case-insensitive.

## Reset the bubble position

If you dragged the mic bubble somewhere awkward, reset it:

`WhisperType → Settings → Reset bubble position`

The bubble returns to its default position.

## Run the compatibility test

1. Open WhisperType.
2. Tap `Compatibility test`.
3. Focus the sample text field.
4. Wait for your normal keyboard to appear.
5. Confirm that the WhisperType dock appears at the keyboard boundary.
6. Tap the dock.
7. Confirm that the voice panel covers the keyboard.
8. Tap Stop.
9. Confirm that the sample text field receives the result.
10. Confirm that the original keyboard returns.

If the test fails, use `Settings → Troubleshooting` and export diagnostics. Diagnostics contain timing and error codes, not transcripts, audio, API keys, or editor text.

## Daily use

1. Open an app such as Messages, Gmail, Chrome, Notes, or another supported application.
2. Tap inside a normal text field.
3. Wait for your normal keyboard to appear.
4. Tap the WhisperType docked microphone.
5. The keyboard area changes into the WhisperType voice panel.
6. Speak naturally.
7. Tap `Stop`.
8. Wait while WhisperType finalizes the transcript.
9. WhisperType inserts the result at the current cursor or replaces the selected text.
10. The normal keyboard returns.

The voice panel does not display transcript text. It displays the waveform, recording state, and optional elapsed time.

## Cancel a dictation

You can cancel by:

- tapping `Cancel` in the voice panel,
- tapping `Cancel` in the recording notification,
- pressing the Android Back button while the voice panel is active,
- locking the phone,
- switching to another app or field.

Cancellation discards the pending result and does not insert text.

## If direct insertion is unavailable

Some fields do not expose a safe editable connection.

Examples include:

- password fields,
- payment fields,
- secure PIN fields,
- private browsing fields,
- unusual WebViews,
- canvas-based editors,
- remote desktop applications,
- unsupported floating or split keyboards.

In these cases:

1. WhisperType finishes the transcription.
2. It does not insert into the field.
3. Tap `Copy`.
4. Return to the text field.
5. Paste using SwiftKey, Gboard, or Samsung Keyboard.

WhisperType never silently pastes into an uncertain field.

## Secure fields

WhisperType intentionally does not appear in:

- password inputs,
- PIN inputs,
- payment fields,
- banking authentication fields,
- secure login fields.

This protects sensitive information and avoids accidental recording.

## Optional history

History is disabled by default. Transcript text is recorded only while it is enabled.

To enable it:

1. Open `Settings`.
2. Tap `Privacy and history`.
3. Enable `Local history`.
4. Choose a retention period (in days).

When enabled, history is encrypted and stored locally. Audio is never stored.

To view or manage it:

`Settings → Privacy and history → View history`

The list is viewable in-app: copy an entry, delete a single entry, or `Clear all history` to empty it. Entries are automatically pruned once they are older than the retention period.

To remove everything at once:

`Settings → Privacy and history → Clear all history`

## Troubleshooting

### The dock does not appear

Check:

1. WhisperType Accessibility Service is enabled.
2. Microphone permission is enabled.
3. Gemini API key is saved.
4. The text field is not secure.
5. The keyboard is fully visible.
6. The keyboard is a supported docked keyboard.
7. WhisperType is not disabled for the current app.
8. Android has not stopped the Accessibility Service.

Reopen:

`Settings → Accessibility → Installed/Downloaded apps → WhisperType`

### The dock disappears

The dock hides when:

- the keyboard closes,
- the text field loses focus,
- the screen locks,
- an unsupported keyboard layout is detected,
- the current field is secure,
- the Accessibility Service disconnects.

This is expected behavior.

### The voice panel does not start

Check:

1. Microphone permission.
2. Notification permission.
3. Gemini API key.
4. Network connection.
5. Whether another app is using the microphone.
6. Whether the recording notification appears.

### Text was copied instead of inserted

The application likely did not expose a reliable input connection. Tap `Copy`, return to the field, and paste manually.

### Samsung stops showing the dock

Open:

`Settings → Accessibility → Installed apps → WhisperType`

Confirm the service remains enabled.

If the device has aggressive battery management, open:

`Settings → Apps → WhisperType → Battery`

Use the least restrictive setting that Samsung provides for the installed version. Do not disable battery protections unless WhisperType documentation specifically identifies the device as requiring it.

### Gemini connection fails

Check:

- API key is valid.
- The key has Gemini API access.
- The phone has internet access.
- Device date and time are correct.
- No VPN, firewall, or private DNS is blocking the connection.

WhisperType never displays or logs the full authenticated Gemini connection URL.

## Disable Accessibility access

### Pixel / stock Android

`Settings → Accessibility → Downloaded/Installed apps → WhisperType → Off`

### Samsung

`Settings → Accessibility → Installed apps → WhisperType → Off`

Disabling the service immediately hides the dock and cancels any active recording.

## Delete the Gemini API key

Open:

`WhisperType → Settings → Gemini API key → Delete key`

Then confirm deletion.

## Uninstall WhisperType

1. Open Android Settings.
2. Tap `Apps`.
3. Tap `WhisperType`.
4. Tap `Uninstall`.
5. Confirm.

Before uninstalling, optionally:

1. Disable Accessibility access.
2. Delete the Gemini API key.
3. Clear local history.
4. Revoke microphone and notification permissions.

## Privacy summary

WhisperType:

- records only after you tap the dock,
- streams audio directly to Gemini Live,
- does not use a WhisperType cloud account,
- does not use a backend server,
- does not store audio,
- does not store history unless you enable it,
- does not read the clipboard,
- does not log complete transcripts,
- does not log API keys,
- does not operate in secure fields,
- requires Accessibility access only for field detection, keyboard positioning, and final text insertion.
