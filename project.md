# SixthSense --- End-to-End Project Specification

> **Current hardware update (2026-11-02):** The working implementation now uses four motors mounted at left, front, right, and back around the head, driven by two DRV8833 boards. This supersedes older three-motor hardware wording below. The current BLE commands are `L`, `F`, `R`, `B`, `A`, and `S`; the forward camera's image-center zone maps to `F`. The back motor is for bench testing and a future rear sensor/camera—never for an inferred blind spot from the forward camera. The implementation details in `architecture.md` and `firmware/esp32_sixthsense/` are the current source of truth.

**Project type:** Android assistive-technology prototype\
**Primary goal:** Convert visual scene information from an Android phone
camera into directional vibration cues delivered by an ESP32 wearable.\
**Application:** Native Android app written in Kotlin\
**Hardware controller:** ESP32 DevKit\
**Motor driver:** DRV8833\
**Status:** Implementation plan and technical specification\
**Important:** This is a prototype specification, not a validated
mobility aid.

------------------------------------------------------------------------

## 1. Project overview

SixthSense is a phone-assisted, haptic-feedback prototype intended to
help a user notice selected objects or obstacles in the direction of
travel. The Android phone runs the camera and vision pipeline. It
determines which horizontal region of the image contains a detected
object, then sends a compact command over Bluetooth Low Energy (BLE) to
an ESP32. The ESP32 drives vibration motors placed at the left,
center/front, and right of a wearable.

The first version deliberately uses **directional zones**, not precise
distance estimation. A normal object detector can identify an object and
its image bounding box, but the box alone does not reliably tell the
real-world distance to that object. Depth estimation and more advanced
policies can be explored after the basic system is working.

### 1.1 Core user experience

1.  The user opens SixthSense and enters Navigation Mode.
2.  The app asks for camera and Bluetooth permissions where required.
3.  The user connects to the wearable named `SixthSense-ESP32`.
4.  The app displays a live camera preview and detection status.
5.  The on-device model detects supported objects.
6.  A zone mapper assigns a relevant detection to `LEFT`, `CENTER`, or
    `RIGHT`.
7.  A haptic policy filters noisy or repeated detections and selects a
    vibration pattern.
8.  The app sends a BLE command to the ESP32.
9.  The ESP32 drives the corresponding motor channel.
10. The app shows connection and detection status and provides a Stop
    control.

The system must clearly indicate when the camera, model, or BLE
connection is unavailable. It must never imply that an undetected area
is safe.

------------------------------------------------------------------------

## 2. Goals and non-goals

### 2.1 Goals for the first working version

-   Native Android app using Kotlin.
-   Live camera preview using CameraX.
-   On-device object detection.
-   Horizontal image-zone mapping.
-   BLE connection and command delivery to an ESP32.
-   Independent left, center, and right vibration control.
-   Motor-test screen.
-   Start/stop controls and visible system status.
-   Basic filtering to reduce vibration chatter.
-   Safe behavior when the connection or camera fails.
-   Repeatable end-to-end testing.

### 2.2 Not in the first version

-   Certified assistive-device behavior or guaranteed obstacle
    avoidance.
-   Precise distance measurement based only on object bounding boxes.
-   Cloud inference as a dependency for core navigation.
-   Automatic route planning or GPS navigation.
-   Guaranteed recognition of every object or hazard.
-   Morse-code, game, and sound-to-haptic modes before the core mode is
    stable.
-   Medical or safety certification.

### 2.3 Later extensions

-   Depth estimation, validated against real-world measurements.
-   Object prioritization and configurable alert policies.
-   Speech output and text reading/OCR.
-   Morse-code practice mode.
-   Game mode for testing haptic patterns.
-   Sound classification and sound-to-haptic alerts for deaf or
    hard-of-hearing users.
-   User-configurable motor strength, pulse duration, and sensitivity.

------------------------------------------------------------------------

## 3. System architecture

``` text
                 ANDROID PHONE
┌───────────────────────────────────────────────────┐
│ UI: Home | Navigation | Motor Test | Settings     │
│                         │                         │
│ CameraX ──> Image Analyzer ──> On-device Detector │
│                                      │            │
│                                      ▼            │
│                         Detection Results         │
│                                      │            │
│                                      ▼            │
│                              Zone Mapper           │
│                                      │            │
│                                      ▼            │
│                          Haptic / Alert Policy     │
│                                      │            │
│                                      ▼            │
│                              BLE Manager           │
└──────────────────────────────┬────────────────────┘
                               │ Bluetooth Low Energy
                               ▼
                         ESP32 DevKit
┌───────────────────────────────────────────────────┐
│ BLE GATT service and command characteristic       │
│ Command parser | watchdog | motor state machine   │
└──────────────────────────────┬────────────────────┘
                               │ GPIO control signals
                               ▼
                      DRV8833 motor driver(s)
                               │
                               ▼
                  Left | Center | Right vibration
                         motor actuators
```

### 3.1 Responsibilities by component

  -----------------------------------------------------------------------
  Component                           Responsibility
  ----------------------------------- -----------------------------------
  Android UI                          Display state, permissions,
                                      preview, detections, connection,
                                      and controls

  CameraX                             Acquire camera frames and manage
                                      camera lifecycle

  Object detector                     Produce labels, confidence scores,
                                      and bounding boxes

  Zone mapper                         Map image coordinates to
                                      left/center/right

  Haptic policy                       Select which detections merit
                                      alerts, apply confidence
                                      thresholds, smoothing, and
                                      cooldowns

  BLE manager                         Scan, connect, discover GATT
                                      services, write commands, and
                                      report connection status

  ESP32 firmware                      Receive commands, validate them,
                                      drive motors, and stop motors on
                                      timeout/disconnect

  Motor driver                        Switch motor current; protect the
                                      ESP32 from motor load

  Vibration motors                    Deliver directional tactile cues
  -----------------------------------------------------------------------

------------------------------------------------------------------------

## 4. Hardware design

### 4.1 Parts list

-   Android phone with a camera and BLE.
-   ESP32 DevKit board.
-   Three vibration motors suitable for the intended supply
    voltage/current.
-   **Two DRV8833 driver boards** if all three motors must be controlled
    independently using the two H-bridge channels per DRV8833;
    alternatively, use a properly designed third transistor/MOSFET
    driver stage for the third motor.
-   Suitable motor power supply matched to the motor and driver
    requirements.
-   Jumper wires, breadboard or soldered connections, and wearable
    mounting material.
-   Optional power switch and enclosure.

Check the exact ESP32 board, motor, and DRV8833 breakout-board
datasheets before wiring. Breakout boards may expose different pin names
or include different supporting components.

### 4.2 Motor-driver constraint

A DRV8833 provides **two H-bridge channels**. Three independently
controlled motors therefore need three suitable driver channels. One
DRV8833 alone is not sufficient for independent control of all three
motors.

Do not connect vibration motors directly to ESP32 GPIO pins. Motor
startup current and inductive/transient behavior can damage the
microcontroller or cause resets. Use a driver and a suitable supply.

### 4.3 Suggested signal allocation

Example GPIO allocation only; verify that the selected pins are suitable
for your exact ESP32 board and do not conflict with boot-strapping or
other board functions.

  ------------------------------------------------------------------------
  Motor                 Driver input/control            Example ESP32 GPIO
  --------------------- --------------------- ----------------------------
  Left motor            Driver channel input                       GPIO 25
                        A                     

  Center motor          Driver channel input                       GPIO 26
                        A on second driver,   
                        or third transistor   
                        stage                 

  Right motor           Driver channel input                       GPIO 27
                        B                     
  ------------------------------------------------------------------------

For a simple one-direction vibration motor, each motor may be controlled
with one driver input if the specific driver-board wiring supports it.
Tie/configure the other input as required by the driver documentation.
Do not assume the example GPIO table is a complete DRV8833 wiring
diagram.

### 4.4 Power and grounding

-   Power motors from a supply that matches their rated voltage and
    current.
-   Connect the motor-supply ground and ESP32 ground together so control
    signals have a common reference.
-   Keep motor-current paths separate from sensitive signal wiring where
    practical.
-   Add local supply decoupling as recommended by the driver-board
    documentation.
-   Verify polarity, current limits, and thermal limits before running
    the motors.
-   Start with short pulses and test one motor at a time.
-   If the ESP32 resets when a motor starts, stop and investigate the
    power supply, wiring, grounding, and current draw.

### 4.5 Physical placement

Mount the motors so the user can reliably distinguish the directions. A
possible arrangement is left side, front/center, and right side of a
belt or upper-body strap. Validate comfort and distinguishability with
short tests. Do not assume the user will interpret a pattern correctly
without practice.

------------------------------------------------------------------------

## 5. Android application design

### 5.1 Recommended stack

-   **Language:** Kotlin.
-   **UI:** Jetpack Compose (recommended for a new project) or the
    existing UI framework if the app already exists.
-   **Camera:** CameraX.
-   **Vision:** Start with an on-device detector compatible with
    Android. ML Kit object detection is one option for a fast prototype;
    a custom TensorFlow Lite model or another Android-compatible model
    may be needed for the required classes or performance.
-   **Bluetooth:** Android Bluetooth Low Energy GATT APIs.
-   **Concurrency:** Kotlin coroutines and lifecycle-aware collection
    where appropriate.
-   **Architecture:** ViewModel plus separate camera, vision,
    navigation-policy, and BLE classes.

Keep detection, zone mapping, alert policy, and BLE code separate. This
makes the app easier to test and allows the detector to be replaced
later.

### 5.2 Suggested source tree

``` text
app/src/main/java/<your-package>/
├── MainActivity.kt
├── ui/
│   ├── HomeScreen.kt
│   ├── NavigationScreen.kt
│   ├── MotorTestScreen.kt
│   └── SettingsScreen.kt
├── viewmodel/
│   └── SixthSenseViewModel.kt
├── camera/
│   └── CameraController.kt
├── vision/
│   ├── ObjectDetector.kt
│   ├── DetectionResult.kt
│   └── ZoneMapper.kt
├── navigation/
│   ├── NavigationEngine.kt
│   ├── HapticPolicy.kt
│   └── HapticCommand.kt
└── bluetooth/
    ├── BleManager.kt
    ├── BleProtocol.kt
    └── BleConnectionState.kt
```

Use the actual package name created by Android Studio in place of
`<your-package>`.

### 5.3 Screens

#### Home screen

-   App name and brief purpose.
-   BLE connection status.
-   Buttons for Navigation Mode, Motor Test, and Settings.
-   Warning that this is an experimental prototype and not a replacement
    for a cane or guide dog.

#### Navigation screen

-   Live camera preview.
-   Start and Stop controls.
-   BLE connection status.
-   Camera/model status.
-   Detected object labels and confidence (for debugging).
-   Current zone and latest haptic command.
-   Clear unavailable/error state if the camera, detector, or BLE link
    fails.

#### Motor Test screen

-   Connect/disconnect controls.
-   Buttons to pulse Left, Center, Right, and All.
-   Stop All button.
-   Short pulses only; avoid a motor running indefinitely.
-   Test results/status for each command.

#### Settings screen

-   Detection confidence threshold.
-   Alert cooldown and pulse duration, within safe tested limits.
-   Enable/disable debug labels.
-   Preferred alert behavior.
-   No setting should suggest that absence of detection means the path
    is clear.

------------------------------------------------------------------------

## 6. Camera and object-detection pipeline

### 6.1 Processing sequence

1.  CameraX supplies a frame to an image-analysis use case.
2.  The analyzer sends the frame to the detector.
3.  The detector returns zero or more results, each with a label (if
    available), confidence, and bounding box.
4.  The analyzer closes/releases the frame as required by the selected
    CameraX/ML API. Failure to release frames can stall the pipeline.
5.  The result is normalized into a detector-independent
    `DetectionResult` model.
6.  The zone mapper assigns each eligible detection to a horizontal
    zone.
7.  The haptic policy decides whether to issue a new alert.
8.  The UI updates on the main thread, and BLE work occurs through the
    BLE manager.

### 6.2 Performance requirements

-   Prefer processing only the latest frame rather than building a long
    queue of stale frames.
-   Configure CameraX backpressure appropriately, such as keeping only
    the latest frame.
-   Avoid running multiple expensive detections simultaneously unless
    the model and device can sustain it.
-   Measure actual frame rate and latency on the target phone.
-   Handle camera rotation, preview cropping, and image-coordinate
    transforms correctly. The displayed preview and detector's
    bounding-box coordinates may not share the same coordinate space.
-   Keep the first version local/offline where possible so the core
    feature does not depend on internet access.

### 6.3 Detector choice

**Prototype option:** ML Kit on-device object detection can help
validate the camera-to-zone-to-haptic flow quickly. Confirm its actual
detection behavior, supported labels, and model configuration on the
target phone; do not assume it will recognize every obstacle or provide
a reliable distance estimate.

**Alternative:** Use a compatible custom object-detection model, such as
an appropriately exported YOLO-family model, if you need specific
classes or greater control. Check Android runtime compatibility, model
size, preprocessing, postprocessing, license, latency, and memory use
before choosing it.

Do not combine multiple models in the first milestone unless the basic
pipeline already works.

------------------------------------------------------------------------

## 7. Direction-zone mapping

The first version uses the horizontal center of each detection's
bounding box.

Let: - `xCenter` be the horizontal center of the detection box. -
`imageWidth` be the width of the image coordinate system used by the
detector. - `r = xCenter / imageWidth`, normalized approximately from 0
to 1.

A simple starting policy is:

  Normalized horizontal position   Zone
  -------------------------------- --------
  `r < 0.33`                       LEFT
  `0.33 <= r <= 0.67`              CENTER
  `r > 0.67`                       RIGHT

These thresholds are a starting point, not a validated ergonomic
standard. Test and adjust them. Account for whether the image is
mirrored and for preview rotation/cropping. If the camera image is
mirrored for a front-facing camera, ensure left/right cues match the
real scene.

### 7.1 Object filtering

A first policy may ignore detections below a configurable confidence
threshold. It can also prioritize only a small set of relevant classes
after confirming the chosen detector actually returns those classes. Use
a neutral `UNKNOWN` result when the detector provides no trustworthy
label.

### 7.2 No distance claims

The zone mapper reports **where an object appears in the image**, not
how far away it is. Do not describe this as accurate distance-based
obstacle detection. If distance estimation is added later, evaluate it
under varied lighting, textures, camera angles, and target distances,
and represent uncertainty explicitly.

------------------------------------------------------------------------

## 8. Haptic policy

The haptic policy translates noisy frame-by-frame detections into
understandable cues.

### 8.1 Initial command set

Use a small command vocabulary:

  Command   Meaning                        Initial behavior
  --------- ------------------------------ ---------------------------------
  `L`       Left zone alert                Short left-motor pulse
  `C`       Center zone alert              Short center-motor pulse
  `R`       Right zone alert               Short right-motor pulse
  `A`       All-motor test/attention cue   Brief pulse on all motors
  `S`       Stop                           Immediately turn all motors off

Example pulse duration: approximately 150--200 ms for initial bench
tests. Tune to the actual motors and wearer feedback; this is not a
clinically validated setting.

### 8.2 Reduce repeated alerts

Without filtering, the same object may trigger a pulse on every camera
frame. The policy should include: - Confidence threshold. - Cooldown per
zone or per tracked object. - Optional persistence requirement, such as
observing a zone for more than one frame before alerting. - Priority
rule if several zones contain detections. - Maximum pulse rate. - A stop
command on navigation stop, BLE disconnect, or fatal pipeline error.

Do not let an old detection remain active indefinitely. Use timestamps
and expire stale results.

### 8.3 Initial policy pseudocode

``` text
onDetectionResults(results, timestamp):
    if navigation is not running:
        return

    eligible = filter by confidence and supported classes
    zones = map eligible bounding boxes to LEFT/CENTER/RIGHT

    if results are stale or detector failed:
        mark vision state unavailable
        do not claim the path is clear
        return

    candidate = choose zone using the configured priority policy

    if candidate exists and its cooldown has expired:
        send matching command through BLE
```

This is a policy outline, not final production code. The choice of
priority and alert frequency must be tested with users and in controlled
conditions.

------------------------------------------------------------------------

## 9. BLE protocol

### 9.1 GATT identifiers

Use the same identifiers in Android and ESP32 firmware:

-   **Service UUID:** `12345678-1234-1234-1234-1234567890ab`
-   **Command characteristic UUID:**
    `12345678-1234-1234-1234-1234567890ac`
-   **Status characteristic UUID:**
    `12345678-1234-1234-1234-1234567890ad`
-   **Advertised device name:** `SixthSense-ESP32`

These are prototype UUIDs. If the project later ships beyond a
prototype, generate and document project-specific UUIDs.

### 9.2 Command format

For the first version, send one ASCII character per command: `L`, `C`,
`R`, `A`, or `S`.

Examples: - Android writes `L` → ESP32 pulses left motor. - Android
writes `C` → ESP32 pulses center motor. - Android writes `R` → ESP32
pulses right motor. - Android writes `S` → ESP32 immediately stops all
motors.

Keep commands short and deterministic. Do not transmit a whole camera
frame over BLE.

### 9.3 Connection lifecycle

1.  Request runtime permissions appropriate for the Android version.
2.  Scan for `SixthSense-ESP32`.
3.  Stop scanning when the target is found or a timeout occurs.
4.  Connect using GATT.
5.  Discover services.
6.  Confirm the expected service and command characteristic exist.
7.  Enable status notifications if implemented.
8.  Mark the link connected only after service discovery succeeds.
9.  Write commands using the API appropriate for the Android SDK level.
10. Handle write callbacks/errors and disconnects.
11. Close/release the GATT client when disconnecting or the relevant
    lifecycle ends.

Do not treat "device found" as equivalent to "ready to receive
commands."

### 9.4 Android permissions

BLE permissions differ by Android version. In broad terms: - Android 12
and newer uses runtime Bluetooth permissions such as scan and connect. -
Older versions may require location permission for BLE scanning,
depending on the OS/API and scan configuration. - Camera permission is
needed for CameraX. - Declare the appropriate manifest permissions and
request runtime permissions at the right time. - Test on the actual
Android version and target SDK.

Use the official Android documentation and the exact dependencies/API
signatures for the chosen compile SDK. Do not copy permission
declarations blindly from an older sample.

### 9.5 Reliability behavior

-   If BLE is disconnected, stop issuing navigation commands and display
    `WEARABLE DISCONNECTED`.
-   Send `S` when the user presses Stop.
-   ESP32 firmware should independently stop motors if commands become
    stale or the BLE connection is lost.
-   Do not assume a command succeeded just because the app attempted a
    write; check callbacks or implement an acknowledgement/status
    characteristic.
-   Avoid queuing many old haptic commands during a slow or interrupted
    connection.

------------------------------------------------------------------------

## 10. ESP32 firmware design

The firmware should be small and predictable.

### 10.1 Firmware responsibilities

-   Advertise the BLE device name.
-   Expose the specified GATT service and characteristics.
-   Parse only the documented commands.
-   Reject unknown commands.
-   Turn off all motors before applying a new command, unless a
    deliberate pattern requires otherwise.
-   Use non-blocking timing/state logic where practical.
-   Stop all motors when the user sends `S`.
-   Stop all motors when BLE disconnects or a command watchdog expires.
-   Publish a status value or acknowledgement if implemented.
-   Avoid leaving a motor on due to a stalled command or blocking delay.

### 10.2 Suggested state machine

``` text
DISCONNECTED
    └── BLE connected → READY

READY
    ├── L/C/R/A received → PULSING
    ├── S received       → READY (all motors off)
    └── BLE disconnect   → DISCONNECTED (all motors off)

PULSING
    ├── pulse duration ends → READY (all motors off)
    ├── S received          → READY (all motors off)
    └── BLE disconnect      → DISCONNECTED (all motors off)
```

Use a watchdog or timeout so a motor cannot remain on indefinitely. A
simple command implementation may pulse locally on the ESP32 rather than
requiring the phone to send a separate stop command for every pulse.

### 10.3 Firmware test checklist

-   Every command controls the intended motor.
-   `S` turns all motors off immediately.
-   Unknown bytes do not activate motors.
-   A disconnect stops every motor.
-   A timeout stops every motor.
-   Motor pulses do not reset the ESP32.
-   The BLE device can reconnect after disconnecting.
-   Commands do not overlap unpredictably.

------------------------------------------------------------------------

## 11. Data models and module boundaries

Use small models so camera, detection, and BLE code are not tightly
coupled.

### `DetectionResult`

-   `label: String?`
-   `confidence: Float`
-   `boundingBox`: coordinates in a documented image coordinate system
-   `timestampMs: Long`

### `DirectionZone`

-   `LEFT`
-   `CENTER`
-   `RIGHT`
-   `NONE`

### `HapticCommand`

-   `LEFT`
-   `CENTER`
-   `RIGHT`
-   `ALL`
-   `STOP`

### `BleConnectionState`

-   `DISCONNECTED`
-   `SCANNING`
-   `CONNECTING`
-   `DISCOVERING_SERVICES`
-   `CONNECTED`
-   `ERROR`

### `VisionState`

-   `STOPPED`
-   `STARTING`
-   `RUNNING`
-   `UNAVAILABLE`
-   `ERROR`

Expose state to the UI using a lifecycle-aware state holder, such as
`StateFlow` in a ViewModel. Avoid letting UI composables directly own
long-lived BLE or camera resources.

------------------------------------------------------------------------

## 12. Error handling and safety

This project is experimental. A false negative, camera obstruction, poor
lighting, detector error, phone movement, BLE interruption, or motor
confusion can cause missed or misleading feedback.

Required safeguards: - Clearly label the app as a prototype. - Do not
claim that the app detects all obstacles. - Do not present "no
detection" as "clear path." - Stop motor output when navigation is
stopped. - Stop motor output on BLE disconnect or stale command
timeout. - Show camera/model/BLE failure states clearly. - Test while
stationary and in a controlled environment first. - Keep a conventional
mobility aid and a sighted test partner available during early tests. -
Do not test the prototype in traffic, near stairs, near platforms, or in
other hazardous environments. - Do not use it as a replacement for a
white cane, guide dog, or trained mobility skills. - Provide an obvious
emergency Stop control. - Make the motor patterns distinguishable and
comfortable before extended use.

------------------------------------------------------------------------

## 13. Testing strategy

### 13.1 Unit tests

Test zone mapping using fixed bounding boxes: - Left-side box maps to
`LEFT`. - Centered box maps to `CENTER`. - Right-side box maps to
`RIGHT`. - Box at each threshold behaves as documented. - Invalid
dimensions and out-of-bounds boxes are handled. - Mirroring/rotation
transforms are tested separately.

Test haptic policy: - Low-confidence detections are filtered. -
Cooldowns suppress repeated alerts. - Stale results expire. - Stop state
prevents new commands. - Multiple detections follow the defined priority
policy.

### 13.2 Hardware-in-the-loop tests

-   Connect phone to ESP32.
-   Pulse each motor separately.
-   Verify the physical left/right placement matches the app's command.
-   Confirm `S` stops all motors.
-   Disconnect BLE while a pulse is active and confirm motors stop.
-   Turn off Bluetooth and confirm the UI reports the loss.
-   Test low battery and motor-start current behavior.
-   Reconnect and verify recovery.

### 13.3 Camera/vision tests

-   Test objects in left, center, and right parts of the image.
-   Test multiple objects in different zones.
-   Test poor and bright lighting.
-   Test a moving phone and moving objects.
-   Test portrait/landscape rotation if supported.
-   Verify that preview coordinates and detector coordinates are
    aligned.
-   Measure detection latency and frame rate.
-   Record false positives and false negatives.

### 13.4 End-to-end acceptance criteria

The first version is ready for a controlled demo when: 1. The app can
connect to the intended ESP32. 2. All three motors can be independently
tested. 3. The camera preview remains responsive. 4. At least the
selected test objects can be detected under documented conditions. 5.
The same detections map consistently to the correct directional zones.
6. Haptic cooldowns prevent constant repeated buzzing. 7. Stop,
disconnect, and error states reliably disable motor output. 8. The UI
clearly shows when any critical subsystem is unavailable. 9. Known
limitations are documented and demonstrated honestly.

------------------------------------------------------------------------

## 14. Recommended implementation order

This is a dependency-based order, **not a time schedule**. Finish and
test each milestone before moving to the next.

### Milestone 1 --- Confirm hardware

-   Identify the exact ESP32 board and DRV8833 breakout boards.
-   Confirm motor voltage/current and supply.
-   Build and test a safe driver circuit.
-   Verify independent control of all three motors.

### Milestone 2 --- ESP32 firmware

-   Implement motor outputs and short pulses.
-   Add BLE GATT service and command parsing.
-   Add stop-on-disconnect and watchdog behavior.
-   Test each command independently.

### Milestone 3 --- Android BLE

-   Create the Kotlin project.
-   Add permissions and BLE scanning.
-   Connect to the ESP32 and discover the service.
-   Add the Motor Test screen.
-   Confirm the phone can trigger all three motors reliably.

### Milestone 4 --- Camera preview

-   Add CameraX.
-   Request camera permission.
-   Verify preview lifecycle and orientation.
-   Keep the UI responsive when switching screens.

### Milestone 5 --- Detection

-   Integrate one on-device detector.
-   Display bounding boxes and labels for debugging.
-   Measure real device performance.
-   Document the supported classes and limitations.

### Milestone 6 --- Zone mapping

-   Normalize coordinates.
-   Implement left/center/right zones.
-   Verify mirrored-camera behavior.
-   Add unit tests.

### Milestone 7 --- Haptic policy

-   Add confidence filtering, persistence, cooldown, and priority rules.
-   Connect the selected zone to a BLE command.
-   Ensure Stop and subsystem failures stop feedback.

### Milestone 8 --- End-to-end integration

-   Run camera → detection → zone → haptic policy → BLE → motor.
-   Test repeated detections, dropped connection, and app lifecycle.
-   Tune the experience only after collecting controlled test results.

### Milestone 9 --- Additional modes

-   Add OCR/text reading, Morse code, game mode, or sound alerts as
    separate modules.
-   Do not allow experimental extra modes to destabilize Navigation
    Mode.

------------------------------------------------------------------------

## 15. Development setup

1.  Install a current stable Android Studio version.
2.  Create a native Kotlin Android project. Jetpack Compose is a good
    default for a new app.
3.  Choose a minimum SDK compatible with the selected CameraX, detector,
    and BLE implementation.
4.  Add CameraX and the selected on-device detector dependencies.
5.  Implement BLE against the Android SDK level selected by the project.
6.  Build and install a minimal app before adding all features.
7.  Install the Arduino IDE or PlatformIO for ESP32 firmware.
8.  Select the correct ESP32 board package and verify a basic serial
    upload.
9.  Keep the Android app and firmware UUIDs/command definitions in sync.
10. Use Git and commit each milestone separately.

Dependency versions should be selected and verified at implementation
time rather than frozen in this specification.

------------------------------------------------------------------------

## 16. Definition of done

The core project is considered implemented when the following complete
path works reliably in controlled testing:

``` text
Camera frame
   ↓
On-device object detection
   ↓
Detection filtering
   ↓
Left / Center / Right zone mapping
   ↓
Haptic policy and cooldown
   ↓
BLE command write
   ↓
ESP32 command validation
   ↓
Motor driver
   ↓
Correct vibration motor
```

The implementation must also demonstrate safe stop behavior, clear error
reporting, motor independence, and documented limitations. A successful
demo does not prove that the device is safe or reliable enough for
independent mobility.

------------------------------------------------------------------------

## 17. Technical references

Use the current official documentation when implementing APIs:

-   Android CameraX: https://developer.android.com/media/camera/camerax
-   Android Bluetooth permissions:
    https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
-   Android Bluetooth Low Energy:
    https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
-   ML Kit object detection:
    https://developers.google.com/ml-kit/vision/object-detection
-   ESP32 documentation: https://docs.espressif.com/
-   Texas Instruments DRV8833 product documentation:
    https://www.ti.com/product/DRV8833

------------------------------------------------------------------------

## 18. Open decisions to confirm before coding

These are implementation choices, not blockers to understanding the
architecture:

1.  **Android project:** New project or existing Kotlin project?
2.  **UI framework:** Jetpack Compose or XML layouts?
3.  **Vision model:** ML Kit for the first prototype, or a specific
    custom model?
4.  **ESP32 framework:** Arduino IDE or PlatformIO?
5.  **Motor electronics:** Two DRV8833 boards, or one DRV8833 plus a
    suitable third motor driver stage?
6.  **Wearable placement:** Belt, shoulder strap, or another
    arrangement?
7.  **Supported objects:** Which objects should trigger a vibration in
    the first demo?

Confirm these choices before finalizing exact dependency versions, GPIO
wiring, and code. Hardware wiring must follow the actual board and motor
datasheets.
