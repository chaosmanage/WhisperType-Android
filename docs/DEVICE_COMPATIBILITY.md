# WhisperType Device Compatibility

## Introduction

WhisperType's core behavior — the dock overlay, keyboard geometry, and text insertion — depends on the device, keyboard, and navigation mode in ways emulators cannot reproduce. This matrix tracks every supported phone/keyboard/navigation combination and its manual acceptance result (Implementation Plan §18.4, §22.6).

Each row must pass the manual device acceptance sequence in `docs/TESTING.md` before a release. Physical-device validation is required; emulators are not a substitute.

## Device baseline

Before testing each device, capture the baseline (Implementation Plan §22.6). Replace `<serial>` with the connected device serial:

```powershell
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell settings get secure default_input_method
```

Record the results in the table below. Never commit device serials or other unique identifiers; the table records only model, Android version, keyboard, and navigation mode.

## Compatibility table

| Phone model | Android version | Keyboard | Navigation mode | Overlay result | Insertion result | Known issues | Last tested date |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel | — | Gboard | Portrait | Pending | Pending |  | — |
| Pixel | — | Gboard | Landscape | Pending | Pending |  | — |
| Pixel | — | SwiftKey | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | Samsung Keyboard | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | Gboard | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | SwiftKey | Portrait | Pending | Pending |  | — |

Add additional rows (e.g. three-button navigation, other keyboards, additional OEMs) as they are validated.

## How to record results

- Fill `Android version` and `Keyboard` with the exact values from the baseline commands (include the keyboard version where the OEM exposes it).
- `Overlay result` / `Insertion result`: `Pass` or `Fail` after the manual acceptance sequence; leave `Pending` until tested.
- `Known issues`: describe any failures or workarounds, referencing the acceptance step that failed (e.g. "step 12 fails: dock flickers on rotation with Samsung Keyboard").
- `Last tested date`: use `YYYY-MM-DD`; keep it `—` until the first full run.
- Update the row for the build version tested; record which build passed (see step 20 of the acceptance sequence in `docs/TESTING.md`).
