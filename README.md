# G-Pulse HUD

An open-source Android companion app for Casio Bluetooth watches (built and tested on the **Casio GBM-2100 CasiOak** series). 

I built **G-Pulse** because I wanted my analog watch hands to actually do something useful when someone calls me. Instead of just vibrating on my desk, the watch physically swings both the hour and minute hands to point at a specific marker on the dial corresponding to who is calling — like a physical analog caller ID HUD right on my wrist. When the call ends or is answered, the hands sweep back to the exact real time.

---

## What It Does

### 1. Analog Caller HUD (4 Dial Positions)
Assign your VIP contacts to four physical dial markers:
- **12:00** — Top marker
- **03:15** — Right marker
- **06:30** — Bottom marker
- **09:45** — Left marker

When an incoming call arrives, G-Pulse matches the caller against your VIP slots in under ~250ms and sends a priority BLE command to immediately park the hands at that position. Once the call drops or finishes, the hands automatically sync back to accurate time.

### 2. Built-in Casio Watch Hardware Controls (Tab 2)
No need to keep Casio's proprietary app installed. G-Pulse communicates directly with the watch over raw BLE:
- **5 Independent Alarms:** Configure alarm times and toggle on/off.
- **Hourly Chime (SIG):** Turn the hourly beep on or off.
- **Key Button Tone:** Mute or unmute the button operation beep.
- **Auto Light (Tilt-to-Light):** Automatically illuminate when tilting your wrist.
- **24-Hour Time Format:** Switch between 12h and 24h display modes.
- **Power Saving Mode:** Enable or disable Casio's smart standby sleep mode.
- **One-Tap Real Time Sync:** Manually trigger a quick time calibration anytime.

### 3. Fast & Reliable Connection
- **Zero-lag hand movement:** Pre-warms timezone offsets during the initial handshake so call alerts execute with a single, immediate BLE write without waiting behind battery pings.
- **Persistent Low-Power Service:** Runs silently in the background with a minimal foreground notification and auto-reconnects when the watch comes back in range.
- **Clean OLED Dark UI:** Pure `#000000` monochrome theme designed with Jetpack Compose.
- **One-Tap Contact Picker:** Tap the contact icon next to any slot to grab numbers directly from your phone's address book.

---

## Supported Watches

Primarily developed and tested on:
- **Casio G-Shock GBM-2100** (Module 5611 / 5689 compatible)
- Built on top of the open-source Casio BLE protocol library (`org.avmedia.gshockapi`). Other Bluetooth-enabled G-Shock models with two-hand motorized movements may also work.

---

## How to Set It Up

1. **Install the APK:** Download the latest `app-debug.apk` from the Releases section or build it locally.
2. **Grant Permissions:** Open G-Pulse and grant Phone, Call Log, Contacts, Bluetooth, and Notification permissions so it can detect incoming calls.
3. **Put Your Watch in Pairing Mode:** Hold down the **CONNECT** button (lower-left button on the GBM-2100) for about 3 seconds until the watch beeps and the display shows `CNCT`.
4. **Connect:** Tap **Connect / Pair Watch** in the app. Once connected, your watch model and battery percentage will show at the top.
5. **Assign VIPs:** Pick contacts for positions `12:00`, `3:15`, `6:30`, or `9:45`.

---

## Building From Source

```bash
# Clone the repository
git clone https://github.com/seninalikp/G-pulse.git
cd G-pulse

# Build debug APK
./gradlew assembleDebug
```

The compiled APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License

Apache License 2.0. Feel free to use, modify, and contribute!

---

*Maintained by Senin Ali KP*
