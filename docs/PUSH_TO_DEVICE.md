# Push to Device (ADB over Tailscale)

How to connect to the test phone over ADB through the Tailscale tailnet and push the
current build. This is the normal deployment loop for the WhisperType rebuild on a
phone that is not plugged in via USB.

Companion doc: `docs/WIRELESS_ADB.md` covers pairing/ports in more depth. This file is
the end-to-end "get the new APK onto the phone" workflow.

---

## 1. Why Tailscale makes this work

- The phone runs the **Tailscale** app and joins the tailnet, so it has a stable
  **`100.x.y.z` address** reachable from this machine — no USB cable, no same-Wi-Fi
  requirement.
- ADB's **Wireless debugging** feature exposes a pairing port and a connect port on
  that Tailscale address.
- Because the address is stable, the workflow below stays the same across reboots
  (only the connect port may change after the phone reboots; see §5).

> Note: The Tailscale tunnel is only used to reach the phone. It does not need to be
> (and is not) a full VPN — normal internet traffic from the phone is unaffected.

---

## 2. One-time: enable Wireless debugging + pair

On the phone:

1. **Settings → Developer options → Wireless debugging** — turn it ON.
2. Tap **"Pair device with pairing code"**. Note the **pairing port** and the
   **6-digit code** (they rotate every few minutes).
3. From this machine:

   ```bash
   printf '<CODE>\n' | adb pair <PHONE_TAILSCALE_IP>:<PAIRING_PORT>
   ```

   Example:

   ```bash
   printf '102237\n' | adb pair 100.127.110.79:46585
   # → Successfully paired to 100.127.110.79:46585
   ```

4. On the Wireless debugging main screen, read the **"IP address & Port"** — this is
   the **connect port** (different from the pairing port).

---

## 3. Connect

```bash
adb kill-server 2>&1
adb start-server 2>&1
adb connect <PHONE_TAILSCALE_IP>:<CONNECT_PORT>
adb devices
```

Example:

```bash
adb connect 100.127.110.79:33395
# → connected to 100.127.110.79:33395
adb devices
# → 100.127.110.79:33395   device
```

---

## 4. Push the current build

Build first if needed:

```bash
cd /workspace/projects/WhisperType-Android
./gradlew assembleDebug
```

Then install, preserving app data (`-r` = reinstall; keeps settings/permissions):

```bash
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

Example:

```bash
adb -s 100.127.110.79:33395 install -r app/build/outputs/apk/debug/app-debug.apk
# → Success
```

Useful variations:

| Goal | Command |
| --- | --- |
| Fresh install, clear data | `adb -s <SERIAL> uninstall com.whispertype.android && adb -s <SERIAL> install app/build/outputs/apk/debug/app-debug.apk` |
| Launch the app | `adb -s <SERIAL> shell am start -n com.whispertype.android/.MainActivity` |
| Check package path | `adb -s <SERIAL> shell pm path com.whispertype.android` |
| Service state | `adb -s <SERIAL> shell dumpsys activity services com.whispertype.android` |
| APK SHA-256 | `sha256sum app/build/outputs/apk/debug/app-debug.apk` |

> Do not `uninstall` during upgrade tests — it wipes the stored Gemini credential and
> permissions. Use `install -r` unless a clean slate is intentional.

---

## 5. If the device drops or goes offline

Wireless ADB sessions drop (especially on Samsung, and always after a phone reboot).
When `adb devices` shows `offline` or is empty:

1. Re-establish the link:

   ```bash
   adb kill-server 2>&1; adb start-server 2>&1; sleep 1
   adb connect 100.127.110.79:33395 2>&1
   sleep 3; adb devices
   ```

2. If the phone **rebooted**, the connect port likely changed. Ask the user to open
   **Wireless debugging** again and read the new "IP address & Port", then connect to
   that.
3. If pairing expired, re-pair with a fresh code (§2).
4. Check reachability of the Tailscale address first if the connect keeps failing:

   ```bash
   ping -c 2 100.127.110.79
   timeout 5 bash -c 'echo > /dev/tcp/100.127.110.79/<CONNECT_PORT>' && echo open
   ```

---

## 6. Reverse tunnel for local web content (gate page)

To serve a page that lives on this machine to the phone's Chrome over localhost, use
`adb reverse` (survives until the connection drops; re-run after reconnect):

```bash
adb -s <SERIAL> reverse tcp:9090 tcp:9090
adb -s <SERIAL> shell am start -a android.intent.action.VIEW \
  -d http://localhost:9090/whispertype-gate.html com.android.chrome
```

This is how the Phase 3/4 field-matrix page is served without the host being reachable
directly from the phone.

---

## 7. Scoped logs during a test

```bash
# Clear the buffer first (does NOT touch app data)
adb -s <SERIAL> logcat -c

# Resolve the app pid (re-resolve after a restart)
adb -s <SERIAL> shell pidof com.whispertype.android

# Follow that process's logs only
adb -s <SERIAL> logcat --pid=<PID>
```

---

## 8. Current device reference (verified 2026-08-05)

- **Device:** Samsung Galaxy S25 (`SM-S921B`), Android 16 (SDK 36), 1080x2340 @ 480dpi
- **Tailscale IP:** `100.127.110.79`
- **ADB connect port:** `33395` — verified working for the Phase 6 wire-fix push; a TCP check and `adb connect` + `install -r` succeeded from the build container (may change after reboot — re-read from Wireless debugging)
- **Package:** `com.whispertype.android` (currently `versionName 0.2.13`, `versionCode 15`)
- **Branch:** `rebuild/clean-runtime`
- **APK:** `app/build/outputs/apk/debug/app-debug.apk`

> Versioning: the app version is bumped on every commit (patch increment of
> `versionName`, +1 `versionCode` — see `CONTRIBUTING.md` §Versioning). Refresh
> this reference whenever the version changes so the recorded version always
> matches the installed build.

### Container note

Tailscale runs on the **host VPS**, not inside this build container (the container's
own `tailscale` client reports `Logged out`). That does not matter: the host routes the
tailnet, so `adb connect 100.127.110.79:33395` and all `adb` commands work from the
container unchanged. Do not treat the container's `tailscale status` as a failure signal
— check TCP reachability instead:

```bash
timeout 5 bash -c 'echo > /dev/tcp/100.127.110.79/33395' && echo open
```
