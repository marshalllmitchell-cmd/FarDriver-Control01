# FarDriver Control — Android prototype v0.1

Native Android Studio project using Kotlin and Jetpack Compose. Four responsive screens: Ride, Tuning, Diagnostics, My Setup. Landscape has a collapsible navigation menu. UI supports Fahrenheit-first placeholders.

## Open and build
1. Install Android Studio with Android SDK 35 and JDK 17 (on a development machine).
2. Open this folder as an Android Studio project. Let Gradle sync (network required for dependencies).
3. Run on an Android device (Android 8.0+). Android 12+ requests Bluetooth Scan/Connect at runtime.
4. Diagnostics > Scan & Connect attempts to find a BLE module with Far/BLE/JDY in its advertised name, then subscribes to notifications on service FFE0 / characteristic FFEC.

## IMPORTANT: prototype limitations
- No APK included; no Android SDK or Gradle build performed in this environment. Compilation and hardware behavior are UNVERIFIED.
- The BLE UUIDs come from prior analysis of decompiled FarDriver code, but characteristic properties and actual controller variants require validation. Device name matching is a heuristic and can miss modules; no reliable reconnect yet.
- Incoming notifications are displayed as raw hex only. Framing, checksum/CRC, telemetry interpretation and fault decoding must be validated with recorded controller traffic before enabling live numeric readings.
- Tuning sliders are **visual placeholders only**, 0–100 arbitrary scale. They do not send any data. Do not interpret their values as safe hardware settings.
- My Setup fields are editable but in-memory only. No profile persistence, ride log persistence, comparisons, or safety engine yet.
- Logging panel is a UI placeholder; it does not actually record samples yet.
- No firmware operations, authentication bypass, controller writes, or safety-critical controls.

## Next development steps
1. Test scan and notifications with ND72530; log BLE device names, service/characteristic properties, and frames.
2. Add verified frame parser and read-only telemetry decoder with fixture tests for both controller protocol generations.
3. Implement persistent setup profiles and timestamped local logs, fault history, safety rules grounded in verified component ratings.
4. Implement comparison and CSV export, then staged parameter editor with read-back verification and safety gating.

This is an independent clean-room-inspired implementation, not a copy of the original app's proprietary source.
