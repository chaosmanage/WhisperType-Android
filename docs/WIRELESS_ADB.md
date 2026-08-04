# Wireless ADB Setup & Push Workflow

## Prerequisites

- VPS (this machine) connected to Tailscale
- Phone connected to the same Tailscale network
- Phone: **Developer Options → Wireless debugging** enabled

## Step 1: Start ADB server

```bash
adb kill-server 2>&1
adb start-server 2>&1
adb devices
```

## Step 2: Pair with the phone

On the phone: **Developer Options → Wireless debugging → Pair device with pairing code**

This shows a **pairing port** and a **6-digit code** (rotates every few minutes).

```bash
printf '<CODE>\n' | adb pair <PHONE_IP>:<PAIRING_PORT>
```

Example:
```bash
printf '102237\n' | adb pair 100.127.110.79:46585
# → Successfully paired to 100.127.110.79:46585
```

**Important:** The pairing port (shown when you tap "Pair device") is DIFFERENT from the connect port (shown under "IP address & Port" on the Wireless debugging main screen).

## Step 3: Connect

Use the **connect port** (the "IP address & Port" shown on the Wireless debugging main screen, NOT the pairing port):

```bash
adb connect <PHONE_IP>:<CONNECT_PORT>
```

Example:
```bash
adb connect 100.127.110.79:38521
# → connected to 100.127.110.79:38521
adb devices
# → 100.127.110.79:38521  device
```

## Step 4: Install APK

```bash
adb -s <PHONE_IP>:<CONNECT_PORT> install -r <APK_PATH>
```

Example:
```bash
adb -s 100.127.110.79:38521 install -r WhisperType-Android-0.2.0-debug.apk
```

## Step 5: Reconnect if dropped

Wireless ADB sessions can drop (especially on Samsung). When `adb devices` shows empty or `device offline`:

1. Check if the port is still reachable: `timeout 5 bash -c 'echo > /dev/tcp/<PHONE_IP>/<PORT>'`
2. If port is open but adb won't connect: `adb kill-server && adb start-server && adb connect <PHONE_IP>:<PORT>`
3. If that fails, the connect port may have changed — re-read it from Wireless debugging settings
4. If pairing expired, re-pair with new code

## Quick reconnect script

```bash
adb kill-server 2>&1; adb start-server 2>&1; sleep 1
adb connect 100.127.110.79:38521 2>&1
sleep 3; adb devices
```

## Phone details (as of 2026-08-04)

- **Tailscale IP:** `100.127.110.79`
- **ADB connect port:** `38521`
- **Pairing port:** varies (shown when tapping "Pair device with pairing code")
- **Device:** Samsung (likely Galaxy S series, Android 15+)
