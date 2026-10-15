# G-Pulse HUD

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue?style=flat-square)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin%202.0-purple?style=flat-square)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/Latest%20Release-v2.0-green?style=flat-square)](https://github.com/seninalikp/G-pulse/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange?style=flat-square)](LICENSE)

An open-source Android companion and analog Caller HUD engineered for Bluetooth-connected Casio watches (built and optimized for the **Casio G-Shock GBM-2100 CasiOak** series).

> **Designed, Engineered & Maintained by [Senin Ali KP](https://github.com/seninalikp)**

---

## The Vision

Standard smartwatches distract you with constant screens, while traditional analog watches give you zero notifications. 

I engineered **G-Pulse** to bridge that gap. When VIP contacts call your phone, G-Pulse commands your Casio watch's physical stepper motors to instantly swing both the analog hour and minute hands to dedicated quadrant markers (`12:00`, `03:15`, `06:30`, or `09:45`). Riders, drivers, and busy professionals can identify who is calling at a glance without ever glancing at a phone screen. The moment the call ends or is answered, both hands automatically glide back to exact local real time.

---

## Key Features

### 1. 4-Quadrant Analog Caller HUD
- **Physical Hand Stacking:** Maps up to 4 VIP contacts or custom phone numbers to cardinal positions:
  - **12:00** — Top Marker (Slot 12)
  - **03:15** — Right Marker (Slot 3)
  - **06:30** — Bottom Marker (Slot 6)
  - **09:45** — Left Marker (Slot 9)
- **Sub-250ms Alert Latency:** Timezone offsets are pre-warmed on initial handshake so incoming call alerts fire in a single atomic BLE write.
- **Priority Dispatch:** Emergency incoming call writes preempt routine battery keep-alive pings so hand movement is instant.
- **Native Contact Picker:** Tap the contact icon (`👤`) next to any slot to grab numbers directly from your address book.

### 2. Complete Casio Hardware Control (No Casio App Required)
Full replacement for proprietary Casio companion apps via raw BLE communication:
- **5 Independent Alarms:** Configure alarm times and toggle on/off. Unset alarms show cleanly as empty slots (`--:--`).
- **Hourly Time Signal (SIG):** Enable or disable the watch's hourly chime beep.
- **Button Operation Tone:** Mute or unmute key-press beeps.
- **Auto Light (Wrist Tilt):** Toggle the automatic LED backlight on wrist angle.
- **12h / 24h Time Display:** Switch the digital LCD between 12-hour and 24-hour military time.
- **Tough Solar Power Saving:** Enable or disable internal standby power saving.
- **One-Tap Real Time & Timezone Calibration:** Searchable world timezone selector and instant calibration trigger.

### 3. Bulletproof Bluetooth Low-Energy Architecture
- **Persistent Foreground Link:** `KeepAliveService` maintains connection in the background with minimal battery footprint.
- **GATT Teardown & Auto-Reconnect:** Exponential backoff loop that cleanly flushes dead GATT handles when moving out of range.
- **Watch Personalization:** Tap the edit icon (`✎`) on the home screen to assign a custom nickname to your watch.

### 4. Distraction-Free OLED Monochrome UI
- Pure `#000000` pitch black and high-contrast `#FFFFFF` stark aesthetic designed for readability under direct sunlight and high battery efficiency on AMOLED screens.

---

## Technical Architecture

```
io.gpulse.hud
├── App.kt                      # Application lifecycle & global initialization
├── BleWatchDiscoverer.kt       # Native BluetoothLeScanner for Casio advertisement packets
├── CallCoordinator.kt          # Fast incoming call listener (TelephonyManager + CallLog fallback)
├── KeepAliveService.kt         # Low-power foreground service managing BLE lifecycle
├── MainActivity.kt             # Modern Jetpack Compose UI (2-Page Tab Segmented Architecture)
├── Prefs.kt                    # Persistent SharedPreferences repository
└── WatchController.kt          # Mutex-isolated BLE orchestrator wrapping GShockAPI
```

- **Language:** Kotlin 2.0+
- **UI Framework:** Android Jetpack Compose + Material 3 Design
- **Concurrency:** Kotlin Coroutines (`CoroutineScope`, `SupervisorJob`, `StateFlow`, `Mutex`)
- **Bluetooth Stack:** Android BLE GATT + Open-source `org.avmedia.gshockapi`
- **Minimum OS:** Android 8.0 Oreo (API 26) — Target SDK: Android 14+ (compileSdk 36)

---

## Direct APK Download

Pre-compiled, ready-to-install binaries are available directly on GitHub Releases:

| Version | Release Type | Direct Download Link |
| :--- | :--- | :--- |
| **v2.0 (Latest)** | Official Public Release | [📦 Download G-Pulse-v2.0.apk](https://github.com/seninalikp/G-pulse/releases/download/v2.0/G-Pulse-v2.0.apk) |
| **v1.0** | Initial Release | [📦 Download G-Pulse-v1.0.apk](https://github.com/seninalikp/G-pulse/releases/download/v1.0.0/G-Pulse-v1.0.apk) |

---

## Build From Source

```bash
# Clone the repository
git clone https://github.com/seninalikp/G-pulse.git
cd G-pulse

# Build Debug APK
./gradlew assembleDebug
```

The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Setup & Pairing Guide

1. Download and install **`G-Pulse-v2.0.apk`** on your Android device.
2. Grant the requested permissions (Phone, Contacts, Call Log, Bluetooth, Notifications).
3. Put your Casio CasiOak into pairing mode by holding the **CONNECT (C)** button (lower-left button) for 3 seconds until it beeps and displays `CNCT`.
4. Open G-Pulse, tap **Scan / Pair Watch**, and tap your watch to connect.
5. Set your VIP contacts on Page 1, or configure alarms and timezone on Page 2!

---

## Author & Credits

- **Creator & Maintainer:** **Senin Ali KP** ([@seninalikp](https://github.com/seninalikp))
- **BLE Protocol Engine:** Built on top of the open-source [gshock-api](https://github.com/izivkov/gshock-api) by Ivan Zivkov.

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
