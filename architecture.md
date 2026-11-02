# SixthSense architecture and implementation guide

This document describes the repository as it exists today and the intended next stages. It is written for a developer or agent joining the project with no earlier conversation context.

## 1. Purpose and safety boundary

SixthSense is an experimental Android-to-wearable haptic prototype. An Android phone obtains visual information and, eventually, will map a relevant detected object to a `LEFT`, `FRONT`, or `RIGHT` vibration cue. An ESP32 receives the cue over Bluetooth Low Energy (BLE) and briefly runs the matching vibration motor through DRV8833 motor drivers. The wearable has four physical motor locations: left, front, right, and back around the head.

The product is **not** a mobility aid, obstacle-avoidance system, or distance-measurement system. A missing detection means only that the current vision pipeline did not report an object; it must never be shown or described as a clear path. Test on a bench or in a controlled stationary environment only, with an immediately accessible physical power switch.

The current milestone is deliberately smaller than the target pipeline:

```text
Implemented now
Android Motor Test UI -> BLE -> ESP32 -> DRV8833 -> one/four motor pulse

Also implemented now
Android Navigation UI -> CameraX rear-camera preview

Implemented when Navigation is started
CameraX frame -> ML Kit detector -> zone mapper -> cooldown -> BLE command
```

## 2. Repository map

```text
project.md                         Original complete project specification
architecture.md                    This implementation guide
README.md                          Setup entry point and current limitations

android/                           Native Android Studio project
  app/src/main/java/com/sixthsense/prototype/
    MainActivity.kt                Activity lifecycle and Compose entry point
    ui/SixthSenseApp.kt            Screens and permission request launchers
    viewmodel/SixthSenseViewModel.kt
                                  App-level state coordinator
    bluetooth/                     BLE protocol, controller, and UI state
    camera/                        CameraX preview lifecycle
    vision/                        Detector interface and zone mapper
    navigation/                    Haptic command model and cooldown helper
    settings/                      DataStore-backed user tuning settings
  app/src/test/                    Unit tests for pure Kotlin components

firmware/esp32_sixthsense/
  esp32_sixthsense.ino            ESP32 Arduino BLE firmware
  README.md                        Firmware requirements and wiring summary
```

`android/` is its own Git repository. Avoid moving Android code to the repository root or changing the package name casually; it is currently `com.sixthsense.prototype`.

## 3. Hardware architecture

### 3.1 Required physical parts

- ESP32 DevKit.
- Two DRV8833 breakout boards. Each board has two independently controllable H-bridges, giving exactly four independent motor channels.
- Four vibration motors whose rated supply voltage and stall current are known.
- A regulated motor power supply matched to the motor rating.
- A 10 kOhm resistor from the shared `SLEEP/EN` line to ground.
- A correctly rated bulk capacitor close to the driver boards. Start with 220–470 uF electrolytic across motor supply positive and ground, observing polarity.
- Wiring, an accessible motor-power switch, and a multimeter.

Do not power a motor from an ESP32 GPIO or its 3.3 V pin. The ESP32 is only a logic controller. Do not connect any motor lead directly to ground; each motor must span both outputs of one H-bridge.

### 3.2 Reference wiring

This mapping is encoded in `firmware/esp32_sixthsense/esp32_sixthsense.ino`.

```text
                    ESP32 DevKit
                 ┌────────────────┐
GPIO 25 -------->│ DRV #1 AIN1    │
GPIO 26 -------->│ DRV #1 AIN2    │---- AOUT1/AOUT2 ---- Left motor
GPIO 27 -------->│ DRV #1 BIN1    │
GPIO 14 -------->│ DRV #1 BIN2    │---- BOUT1/BOUT2 ---- Front motor
GPIO 32 -------->│ DRV #2 AIN1    │
GPIO 33 -------->│ DRV #2 AIN2    │---- AOUT1/AOUT2 ---- Right motor
GPIO 18 -------->│ DRV #2 BIN1    │
GPIO 19 -------->│ DRV #2 BIN2    │---- BOUT1/BOUT2 ---- Back motor
GPIO 13 -------->│ Both SLEEP/EN  │
                 └────────────────┘
                       |
                      10 kOhm
                       |
                      GND

Motor supply + --------------> VM/VCC of both driver boards
Motor supply - ----+---------> GND of both driver boards
ESP32 GND ---------+
```

All four H-bridge channels are now in use. No motor-control input may float.

Breakouts vary. Some label inputs `AIN1/AIN2/BIN1/BIN2`; others use `IN1/IN2/IN3/IN4`, and some expose `SLEEP` while others hard-wire it. This mapping is valid only after comparing the actual board’s documentation and silkscreen. If a board exposes raw DRV8833 pins rather than a complete breakout, do not wire it from this guide alone; follow the TI reference circuit, including required capacitors and current-sense configuration.

### 3.3 Electrical behavior

Each motor uses one H-bridge. The firmware runs motors in a single arbitrary direction because a vibration motor does not need reversible mechanical motion.

```text
IN1 LOW,  IN2 LOW   -> outputs coast; normal off state
IN1 HIGH, IN2 LOW   -> motor on
IN1 LOW,  IN2 HIGH  -> reverse; unused in this project
IN1 HIGH, IN2 HIGH  -> brake; not the normal off state
```

`SLEEP/EN` has a 10 kOhm pull-down so the motor drivers are disabled during ESP32 reset and boot. The firmware brings the line HIGH only for a live pulse, allowing the driver’s wake-up time before energizing an H-bridge.

### 3.4 Hardware test sequence

1. With all power disconnected, inspect continuity and polarity.
2. Connect only ESP32 and driver boards; do not attach a motor yet.
3. Measure motor-supply voltage at the driver board before attaching a motor.
4. Connect one motor and use a BLE testing app to send `L`.
5. Confirm `S` stops it immediately; power-cycle the ESP32 and confirm it does not start the motor.
6. Repeat for front, right, and back motors.
7. Test `A` only after each individual channel is known to work.
8. Disconnect BLE during a pulse and verify that all outputs stop.

If the ESP32 resets, the supply droops, or a driver overheats, stop testing. Check the motor stall current, supply capability, capacitance, wiring, and shared ground before trying again.

## 4. BLE contract

The Android app and ESP32 must remain synchronized on this protocol. Do not change an identifier or command on just one side.

```text
Peripheral name:      SixthSense-ESP32
Service UUID:         12345678-1234-1234-1234-1234567890ab
Command UUID:         12345678-1234-1234-1234-1234567890ac
Status UUID:          12345678-1234-1234-1234-1234567890ad

Command characteristic:
  properties:         Write and Write Without Response
  payload:            exactly one ASCII byte
  accepted commands:  L, F, R, B, A, S

Status characteristic:
  properties:         Read and Notify
  payload:            short ASCII state text
```

Command meanings:

```text
L -> one left-motor pulse
F -> one front-motor pulse
R -> one right-motor pulse
B -> one back-motor pulse
A -> one pulse on all four motors
S -> stop all motors immediately
```

The command characteristic is intentionally compact; no camera frame, JSON, or queued stream is ever sent to the wearable. Motor timing happens on the ESP32, so a delayed or dropped phone-side stop cannot leave a motor intentionally active after the configured pulse duration.

## 5. ESP32 firmware workflow

### 5.1 Dependencies and upload

Open `firmware/esp32_sixthsense/esp32_sixthsense.ino` in Arduino IDE. Install an ESP32 Arduino core 3.x package, select the exact ESP32 board and serial port, then upload. The sketch uses the BLE library bundled with the ESP32 Arduino core and has no hard-coded Wi-Fi credentials or cloud dependency.

### 5.2 Boot sequence

```text
ESP32 reset
  -> configure every motor-input GPIO as OUTPUT
  -> drive inputs LOW and SLEEP/EN LOW
  -> start the standard ESP32 BLE server
  -> create service, command characteristic, and status characteristic
  -> advertise service UUID and the explicit local name SixthSense-ESP32
  -> wait with all drivers disabled
```

This means a restarted microcontroller begins with motors off even before a phone connects.

### 5.3 Command processing and safety state

```text
BLE client connects
  -> firmware stops/disables outputs and publishes CONNECTED

L/F/R/B/A received
  -> validate that payload length is exactly one byte
  -> validate command byte
  -> record the requested command and a 180 ms deadline
  -> main loop wakes SLEEP/EN, waits at least 1 ms, enables matching motor(s)
  -> deadline ends -> outputs low, driver disabled, PULSE_COMPLETE published

S received
  -> outputs low and driver disabled immediately, STOPPED published

Invalid or multi-byte command
  -> ignore motor action, publish INVALID_COMMAND

BLE disconnect
  -> outputs low and driver disabled, then advertising restarts
```

The firmware also has a 1.2 second stale-command watchdog. The short pulse deadline normally stops an active command first; the watchdog is a second guard if the command model changes in a future firmware revision.

`connected`, the active command, and timing values are protected by an ESP32 critical section because BLE callbacks and the Arduino main loop may execute in different tasks. Do not alter this shared state from new callbacks without keeping that synchronization.

### 5.4 Status messages

Status is a debugging and UI aid, not an acknowledgement strong enough to prove physical vibration. Current messages include `CONNECTED`, `PULSE_LEFT`, `PULSE_FRONT`, `PULSE_RIGHT`, `PULSE_BACK`, `PULSE_ALL`, `PULSE_COMPLETE`, `STOPPED`, `INVALID_COMMAND`, `WATCHDOG_STOP`, and `DISCONNECTED`.

An agent adding a new command must update all of these locations together:

1. `HapticCommand` and `BleProtocol.encode()` in Android.
2. The Android Motor Test/UI behavior if applicable.
3. `decodeCommand()` and `nameOf()` in firmware.
4. Firmware tests or bench-test instructions.
5. This document and `project.md` if the protocol meaning changes.

## 6. Android application architecture

### 6.1 UI and lifecycle

`MainActivity` owns a single `SixthSenseViewModel` and renders the Jetpack Compose root `SixthSenseApp`.

```text
MainActivity
  -> SixthSenseApp Compose navigation
       -> Home
       -> Motor Test
       -> Navigation
       -> Settings
  -> onStop()
       -> ViewModel stops Navigation and BLE scanning
```

The UI is not allowed to call Android GATT APIs directly. It observes `StateFlow` values from the ViewModel and invokes intent-like ViewModel methods such as `connectWearable()` or `send(HapticCommand.LEFT)`.

### 6.2 Screen responsibilities

- **Home:** prototype safety warning, BLE status, and navigation entry points.
- **Motor Test:** requests Bluetooth permissions, connects/disconnects, sends each command, and exposes an explicit red `STOP ALL` button.
- **Navigation:** requests camera permission, starts/stops the rear-camera preview, runs ML Kit on-device detection, displays the latest classifications, and sends eligible left/front/right alerts only when the wearable is connected.
- **Settings:** stores confidence threshold, cooldown, pulse duration, and debug-label preference using Android DataStore.

The confidence threshold and cooldown settings are consumed by the current ML Kit haptic policy. The pulse-duration setting is persisted but does **not** change `PULSE_DURATION_MS` in firmware. Do not tell a user it does until a versioned configuration protocol is designed and implemented.

### 6.3 BLE implementation

`bluetooth/BleProtocol.kt` is the Android source of truth for the UUIDs, device name, and ASCII command encoding. `bluetooth/BleController.kt` owns all scanning and GATT resources.

```text
Motor Test: Connect
  -> Android runtime permissions verified
  -> Bluetooth adapter verified enabled
  -> scan with a filter for SixthSense service UUID (12-second timeout)
  -> first matching peripheral is selected
  -> connectGatt(... TRANSPORT_LE)
  -> discover services
  -> require the expected command characteristic
  -> enable optional status notifications when present
  -> publish CONNECTED / commandReady=true to StateFlow

Motor Test: Pulse button
  -> ViewModel records latest command for UI
  -> BleController checks commandReady and no write is in flight
  -> one-byte default-response GATT write
  -> callback clears in-flight state

Motor Test: Disconnect
  -> if ready, write S
  -> short delay gives the stop write a chance to leave the phone
  -> close the GATT connection and clear state

Activity stop
  -> if Navigation is running, stop camera/navigation and send S when possible
  -> cancel an in-progress BLE scan
  -> does not currently close an already established Motor Test GATT connection
```

The controller has no queue. When a command write is in progress, it drops a new command rather than delivering stale vibration requests later. That behavior is correct for navigation feedback.

BLE state is represented by `BleConnectionState` and `BleUiState`:

```text
DISCONNECTED -> SCANNING -> CONNECTING -> DISCOVERING_SERVICES -> CONNECTED
                                                   |                 |
                                                   +-> ERROR <--------+
```

`CONNECTED` is set only after service discovery finds the command characteristic. Discovery failure, write failure, a scan failure, or unexpected disconnect changes the state to `ERROR`; the UI must not treat that as connected.

### 6.4 Permissions

Android 12 and later need `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at runtime. Android 11 and older use `ACCESS_FINE_LOCATION` for BLE scanning. Camera preview needs `CAMERA`. Declarations are in `android/app/src/main/AndroidManifest.xml`; runtime launchers are in `SixthSenseApp.kt`.

Test permissions on a real physical Android phone. Emulators cannot validate the BLE wearable or camera hardware path meaningfully.

## 7. Camera, vision, and haptic-policy design

### 7.1 Existing building blocks

- `CameraController` binds a rear CameraX `Preview` to a lifecycle owner and `PreviewView`.
- `DetectionResult` is the detector-neutral result type: `label`, `confidence`, and normalized horizontal center.
- `MlKitObjectAnalyzer` is the active CameraX `ImageAnalysis.Analyzer`. It uses ML Kit stream mode with coarse classification enabled, processes only one frame at a time, and closes every `ImageProxy` in its completion callback.
- `DetectionSource` and `DebugDetectionSource` remain an unused lightweight seam for a future detector replacement; they are not on the active camera path.
- `DirectionZoneMapper` maps normalized horizontal centers: `< 0.33` left, `0.33..0.67` center, and `> 0.67` right. Values outside `0..1` are `UNKNOWN`.
- `HapticCommandMapper` maps `LEFT` and `RIGHT` image zones directly, and maps the camera's `CENTER` image zone to the physical `FRONT` motor.
- `HapticCooldown` is a pure helper that rate-limits commands with no backlog.

### 7.2 Current vision-to-haptic flow

The next implementation milestone should use this one-way flow:

```text
CameraX ImageAnalysis frame (KEEP_ONLY_LATEST)
  -> exactly one active ML Kit stream-mode invocation
  -> DetectionResult list: coarse label, label confidence, horizontal center
  -> show all results for debugging
  -> choose the highest-confidence result above the saved threshold
  -> DirectionZoneMapper
  -> HapticCommandMapper (image center becomes physical front)
  -> global saved cooldown
  -> BleController.send(command), only when the wearable is command-ready
```

Use CameraX `STRATEGY_KEEP_ONLY_LATEST` to avoid stale queues. Always close `ImageProxy` frames after detector processing, including errors. Keep image-space and preview-space coordinate transformations explicit; visual preview cropping and sensor rotation can otherwise reverse or shift left/center/right mapping.

ML Kit is the selected first detector. Its default classifier supplies broad categories rather than a comprehensive obstacle vocabulary, and may need several stream frames before classifications appear. A future custom TensorFlow Lite/ExecuTorch model may replace it only after the current camera-to-motor path is measured and tested. Do not add depth estimation, OCR, cloud inference, voice, dashboard, or a language model to this milestone.

The forward-facing camera has no information about the real-world area behind the wearer. Therefore, it must never automatically emit `B` from its normal image analysis. `B` currently exists for motor testing and a future rear-facing sensor/camera or explicitly designed navigation mode. A future rear input must have its own documented coordinate transform, policy, and safety evaluation before it drives the back motor.

### 7.3 Required policy decisions before wiring vision to BLE

An implementation agent must decide and document these behaviors before sending live detection commands:

- Supported labels and confidence threshold.
- Candidate priority if several zones contain objects; a conservative front-first policy is a reasonable initial experiment but not a safety guarantee.
- Persistence rule, for example two consecutive frames before an alert.
- Cooldown duration per zone or per object.
- Stale-result timeout.
- Mirroring, rotation, portrait/landscape behavior, and whether the rear camera is mandatory.
- Explicit stop behavior on camera failure, model failure, Activity stop, and BLE disconnect.

No detector output may be interpreted as “all clear.” The current firmware stays silent after a pulse; it does not issue an all-clear haptic signal.

## 8. Interfaces, ownership, and change rules

### 8.1 Ownership

```text
Compose UI
  owns: rendering and permission request launchers
  must not own: BluetoothGatt or long-lived camera resources

SixthSenseViewModel
  owns: UI-facing state coordination and lifecycle decisions
  delegates to: BleController, CameraController, SettingsRepository

BleController
  owns: scan callback, GATT instance, service discovery, write serialization
  must not own: navigation-policy decisions

ESP32 firmware
  owns: motor timing, command validation, physical fail-safe stopping
  must not own: image understanding or route decisions
```

### 8.2 Invariants that must stay true

- A driver starts disabled and every motor is off on reset, disconnect, and invalid command.
- App-side BLE write success does not prove the motor moved; it only proves the GATT write callback succeeded.
- No component queues obsolete haptic commands.
- The app presents subsystem failures clearly and sends/causes a stop for fatal navigation failures.
- Any change to UUIDs, name, command payload, pins, pulse semantics, or status strings is a cross-component change.
- UI settings cannot be represented as active firmware configuration until a validated BLE configuration protocol exists.

## 9. Verification checklist

### 9.1 Static checks

From `android/`, run:

```bash
./gradlew testDebugUnitTest
```

Use Android Studio’s embedded JDK 17. The current execution host has Java 25, which the bundled Gradle/Kotlin tooling cannot parse, so it cannot run the Android test task here until a compatible JDK is selected.

For firmware, compile only after the ESP32 Arduino core is installed:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/esp32_sixthsense
```

Replace `esp32:esp32:esp32` with the FQBN for the actual selected board if different.

### 9.2 Hardware-in-the-loop checks

1. Flash firmware with motors disconnected; verify the serial log says it is ready.
2. Use nRF Connect to find `SixthSense-ESP32`, inspect the service/characteristics, and send each ASCII command.
3. Attach one motor at a time and confirm its physical position matches `L`, `C`, or `R`.
4. Verify `S` turns every motor off immediately.
5. Disconnect the BLE client while a pulse is active; verify motors stop.
6. Install the Android app and repeat the test through the Motor Test screen.
7. Keep the device stationary and supervised for every initial test.

## 10. Recommended next changes

1. Validate the exact DRV8833 breakouts and motor power ratings against the wiring in this document.
2. Flash and bench-test the firmware with a BLE utility before using the Android app.
3. Build and install the Android app with Android Studio/JDK 17; test only its Motor Test screen.
4. Bench-test the current ML Kit → zone → BLE → motor path with a connected wearable and visible debug labels.
5. Extract the current ViewModel candidate-selection logic into a pure, unit-tested `HapticPolicy`; add persistence and per-zone cooldowns before relying on the experience for longer tests.
6. Test stop/error/rotation/disconnect behavior before adding any extra modes.

Do not skip directly to depth, voice, OCR, dashboard, or “all clear” features. The core demo is complete only when the controlled path from camera detection to the correct physical motor is repeatable and its limitations are visible to the user.
