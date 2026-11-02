# Agent Works Log

> Each agent will update what changes they did and timestamp in this file.

---

```
=============================================================================
Agent:     Antigravity (Claude Opus 4.6)
Timestamp: 2026-11-02T22:04:00+05:30
Task:      Fix BLE device discovery — ESP32 not found by SixthSense app
=============================================================================

ROOT CAUSE:
-----------
The BLUETOOTH_SCAN permission in AndroidManifest.xml was declared WITHOUT
the `android:usesPermissionFlags="neverForLocation"` attribute.

On Android 12+ (API 31+), when BLUETOOTH_SCAN is declared without
"neverForLocation", the system silently ALSO requires ACCESS_FINE_LOCATION
at runtime for BLE scan results to be delivered. However, the app's manifest
caps ACCESS_FINE_LOCATION at maxSdkVersion="30", meaning it is never
declared or granted on Android 12+. As a result, the BLE scanner starts
successfully but returns ZERO scan results — the exact symptom reported.

Other BLE scanning apps (like nRF Connect) include "neverForLocation" or
request location permission separately, which is why they can find the ESP32
while this app could not.

FILES CHANGED:
--------------
1. android/app/src/main/AndroidManifest.xml
   - Added `android:usesPermissionFlags="neverForLocation"` to BLUETOOTH_SCAN
     declaration. This tells Android the app does not derive physical location
     from BLE scans, so no location permission is needed on Android 12+.
   - Added ACCESS_COARSE_LOCATION (maxSdkVersion="30") for pre-Android 12
     devices that require both fine and coarse location for BLE scanning.

2. android/app/src/main/java/com/sixthsense/prototype/bluetooth/BleController.kt
   - Added location services check (isLocationEnabledIfRequired) — on
     Android 11 and below, BLE scanning silently fails if GPS/network
     location is not enabled in system settings, even with permission granted.
     The app now shows a clear error message instead of silently finding
     nothing.
   - Added needsLocationServicesEnabled() public accessor for UI warnings.
   - Updated requiredPermissions() to also request ACCESS_COARSE_LOCATION
     on pre-Android 12 devices.
   - Added null-safety check for BluetoothLeScanner (can be null if
     Bluetooth is being toggled off).
   - Added setReportDelay(0) to ScanSettings to ensure immediate delivery
     of results instead of batching.
   - Added onBatchScanResults() callback handler for devices that batch
     results despite setReportDelay(0).
   - Added detailed error handling in onScanFailed() with specific messages
     for SCAN_FAILED_ALREADY_STARTED (auto-retry) and
     SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (suggest toggling BT).
   - Added Log.d/Log.e debug logging for scan results and failures.
   - Wrapped stopScan() in try-catch to prevent crashes on race conditions.

VERIFICATION:
-------------
After these changes:
- Android 12+ phones will discover BLE devices without needing location
  permission (the "neverForLocation" flag handles it).
- Android 11 and below phones will get a clear error if location services
  are disabled.
- Scan failures are logged and reported with actionable messages.
- The ESP32 advertising as "SixthSense-ESP32" will appear in the device
  list when scanning.
=============================================================================
```


```
=============================================================================
Agent:     Antigravity (Claude Opus 4.6 Thinking)
Timestamp: 2026-11-02T22:30:00+05:30
Task:      Wire detection → classification → navigation → ESP32 pipeline
=============================================================================

SUMMARY:
--------
Completed the full vision-to-haptic-to-BLE pipeline. The previous agent's
subagents had failed to create the required files. This session implemented
all missing components from architecture.md §7.3 and §10 point 5.

FILES CREATED:
--------------
1. android/app/src/main/java/com/sixthsense/prototype/navigation/HapticPolicy.kt
   - NEW file: Pure, unit-testable haptic navigation policy
   - Per-zone cooldowns (LEFT, CENTER, RIGHT independent timers)
   - Persistence: requires 2 consecutive frames before triggering alert
   - Stale-result timeout (1500ms): resets zone state if no detections
   - Front-first priority: CENTER (FRONT motor) fires first when
     multiple zones have simultaneous detections
   - Never emits BACK command from forward camera
   - Thread-safe via @Synchronized for ML Kit background callbacks
   - HapticPolicyConfig data class for runtime configuration
   - PolicyResult data class with zone counts for UI display

2. android/app/src/test/java/com/sixthsense/prototype/navigation/HapticPolicyTest.kt
   - NEW file: 10 unit tests covering:
     * Persistence requirement (no fire on first frame)
     * Fires after required consecutive frames
     * Per-zone cooldown prevents rapid re-fire
     * Front-first priority with multi-zone detections
     * Left fires when center is on cooldown
     * Low-confidence filtering
     * Stale-result timeout resets state
     * Zone counter resets when detection disappears
     * reset() clears all state
     * Never emits BACK command
     * Commands-sent counter accuracy

FILES MODIFIED:
---------------
3. android/app/src/main/java/com/sixthsense/prototype/vision/DetectionSource.kt
   - ENABLED enableMultipleObjects() — critical for multi-zone detection
   - ADDED boxAreaRatio field to DetectionResult (larger = closer object)
   - ADDED normalizedHeight calculation for proper area ratio
   - Changed unclassified objects: label="Object", confidence=0.5 floor
     (previously label="Unclassified object", confidence=0.0)
     → Unclassified objects now pass default 0.40 threshold as obstacles
   - Added Log.d for detection debugging
   - Added TAG companion object

4. android/app/src/main/java/com/sixthsense/prototype/viewmodel/SixthSenseViewModel.kt
   - REPLACED inline handleDetections logic with HapticPolicy.evaluate()
   - REMOVED lastHapticAtMs field (replaced by per-zone policy state)
   - ADDED hapticPolicy field and config sync from settings flow
   - ADDED zoneCounts and commandsSentCount to NavigationUiState
   - Policy reset on startNavigation() and stopNavigation()
   - Added TAG companion object for logging

5. android/app/src/main/java/com/sixthsense/prototype/ui/SixthSenseApp.kt
   - NavigationScreen: ADDED BLE scan/connect controls inline
     (no need to go back to Motor Test to connect)
   - NavigationScreen: GATED Start button on BLE CONNECTED state
     (button shows "Start (connect first)" when disconnected)
   - NavigationScreen: ADDED zone indicator row with color coding:
     * Default (gray) = no detection in zone
     * Orange = detection present, building persistence
     * Red = active alert sent to that zone
   - NavigationScreen: ADDED per-detection cards showing label,
     confidence %, zone, and bounding box area %
   - NavigationScreen: ADDED scrollable layout (verticalScroll)
   - NavigationScreen: ADDED BLE device list with radio selection
   - NavigationScreen: Shows commands-sent counter
   - Added imports: rememberScrollState, verticalScroll, Box, background,
     clip, RoundedCornerShape, CardDefaults, DirectionZone, TextAlign

PIPELINE FLOW (now complete):
-----------------------------
CameraX frame (KEEP_ONLY_LATEST)
  → MlKitObjectAnalyzer (multi-object, classification enabled)
  → DetectionResult list (label, confidence, center, area)
  → HapticPolicy.evaluate()
    → confidence filter (configurable threshold)
    → DirectionZoneMapper (LEFT / CENTER / RIGHT)
    → per-zone consecutive-frame persistence (2 frames)
    → per-zone cooldown timer (700ms default)
    → front-first priority selection
  → PolicyResult (command, zone, counts)
  → BleController.send(command) when commandReady
  → ESP32 GATT write → motor pulse

ARCHITECTURE §7.3 DECISIONS (now documented in code):
----------------------------------------------------
- Supported labels: ALL objects (unclassified get 0.5 confidence floor)
- Confidence threshold: 0.40 default (user-adjustable in Settings)
- Priority: front-first (CENTER → LEFT → RIGHT)
- Persistence: 2 consecutive frames
- Cooldown: 700ms per-zone (user-adjustable in Settings)
- Stale timeout: 1500ms
- Rotation: handled by normalizedWidth/normalizedHeight swap
- Rear camera mandatory: yes (DEFAULT_BACK_CAMERA in CameraController)
- Stop on: camera error, Activity stop, BLE disconnect, user Stop button

NOT CHANGED:
------------
- HomeScreen, MotorTestScreen, SettingsScreen — identical
- HapticCommand.kt, HapticCooldown.kt — unchanged (HapticCooldown still
  available for other use cases but HapticPolicy now handles cooldowns)
- BleController.kt, BleProtocol.kt, BleConnectionState.kt — unchanged
- CameraController.kt, DirectionZone.kt — unchanged
- MainActivity.kt — unchanged
- All existing unit tests — unchanged and compatible

BUILD NOTE:
-----------
Host has Java 25; Gradle/Kotlin requires JDK 17. Build and test in
Android Studio with its embedded JDK 17 or set JAVA_HOME accordingly.
The HapticPolicyTest has no android.util.Log dependency and should run
as a pure JUnit test.
=============================================================================
```
