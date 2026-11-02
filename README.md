# SixthSense

SixthSense is an experimental Android assistive-technology prototype that pairs a phone camera with a four-motor ESP32 wearable. Motors are placed at left, front, right, and back around the head. It is not a certified mobility aid and does not replace a cane, guide dog, or attentive travel practices. An absence of detections never means a path is clear.

## Phase 1 Android app

The native Kotlin/Jetpack Compose project is in [`android/`](android/). The default package/application ID is `com.sixthsense.prototype`; set a different installable application ID with `-PsixthSenseApplicationId=your.package.name`. If changing the source package too, use Android Studio’s Rename Package refactor and update the manifest namespace together.

The matching ESP32 Arduino sketch is in [`firmware/esp32_sixthsense/`](firmware/esp32_sixthsense/). It implements the same BLE contract and provides short, locally timed pulses plus disconnect and watchdog stops.

### Setup

1. Open `android/` in a recent Android Studio installation, install Android SDK Platform 35, and let Gradle sync. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select the embedded JDK (version 17 or newer).
2. Run on a physical Android device with a rear camera and Bluetooth LE. A physical device is required for meaningful camera/BLE verification.
3. On the Motor Test screen, grant Bluetooth permission and connect to a peripheral advertised as `SixthSense-ESP32`.
4. Navigation requests camera permission upon entry. Start opens a rear-camera preview and runs ML Kit's bundled on-device object detector. Connect the wearable before testing haptic alerts.

The app requests `CAMERA`. On Android 12+ it requests `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; older supported Android versions request fine location for BLE scanning.

If an Android Studio build stops during DEX merging with `java.lang.OutOfMemoryError: Java heap space`, this project already requests a 2 GB Gradle heap in `gradle.properties`. Reload/sync the project and rebuild; if the machine has sufficient memory but the error persists, increase `-Xmx2048m` to `-Xmx4096m`.

### BLE contract

| Item | Value |
| --- | --- |
| Device name | `SixthSense-ESP32` |
| Service | `12345678-1234-1234-1234-1234567890ab` |
| Command characteristic | `12345678-1234-1234-1234-1234567890ac` |
| Status characteristic | `12345678-1234-1234-1234-1234567890ad` |
| Commands | one ASCII byte: `L`, `F`, `R`, `B`, `A`, `S` |

The controller reports connected only after it finds the expected service and command characteristic. It stops scanning on match, timeout, or disconnect, avoids queuing writes, and exposes write/disconnect failures. The wearable firmware must independently stop motors on disconnect and stale-command timeout.

`F` is the front motor and `B` is the back motor. The current forward-facing camera can only map image-left, image-center, and image-right to `L`, `F`, and `R`; it must not issue `B` until a rear-facing sensor/camera pipeline is deliberately added and tested.

### Current limitations

- ML Kit detects and coarsely classifies prominent objects on-device. It is not a comprehensive obstacle detector and supports only broad default categories; custom objects, distance estimation, cloud services, routing, GPS, dashboard, and voice features are not included.
- The camera preview does not imply detection coverage; no detection is never a clear-path signal.
- Motor pulse timing is currently an ESP32 firmware responsibility. Confirm the actual driver wiring, status characteristic behavior, command write properties, and watchdog implementation before wearable testing.
- This project has not been safety-validated with users or in real travel environments.
