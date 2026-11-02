package com.sixthsense.prototype.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sixthsense.prototype.bluetooth.BleController
import com.sixthsense.prototype.bluetooth.BleUiState
import com.sixthsense.prototype.camera.CameraController
import com.sixthsense.prototype.camera.CameraState
import com.sixthsense.prototype.navigation.HapticCommand
import com.sixthsense.prototype.navigation.HapticCommandMapper
import com.sixthsense.prototype.navigation.HapticPolicy
import com.sixthsense.prototype.navigation.HapticPolicyConfig
import com.sixthsense.prototype.settings.AppSettings
import com.sixthsense.prototype.settings.SettingsRepository
import com.sixthsense.prototype.vision.DetectionResult
import com.sixthsense.prototype.vision.DirectionZone
import com.sixthsense.prototype.vision.DirectionZoneMapper
import com.sixthsense.prototype.vision.MlKitObjectAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.os.SystemClock

data class NavigationUiState(
    val running: Boolean = false,
    val currentZone: DirectionZone = DirectionZone.UNKNOWN,
    val latestCommand: HapticCommand? = null,
    val detections: List<DetectionResult> = emptyList(),
    val visionMessage: String = "Detector stopped",
    /** Per-zone consecutive-frame counts from the policy, for UI zone indicators. */
    val zoneCounts: Map<DirectionZone, Int> = emptyMap(),
    /** Total haptic commands sent this navigation session. */
    val commandsSentCount: Int = 0,
)

class SixthSenseViewModel(application: Application) : AndroidViewModel(application) {
    private val bleController = BleController(application)
    private val settingsRepository = SettingsRepository(application)
    private val cameraController = CameraController()
    private val hapticPolicy = HapticPolicy()
    private val objectAnalyzer = MlKitObjectAnalyzer(
        onDetections = ::handleDetections,
        onError = { error -> handleVisionError(error) },
    )
    val ble: StateFlow<BleUiState> = bleController.state
    val camera: StateFlow<CameraState> = cameraController.state
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    private val _navigation = MutableStateFlow(NavigationUiState())
    val navigation: StateFlow<NavigationUiState> = _navigation.asStateFlow()

    init {
        viewModelScope.launch {
            camera.filterIsInstance<CameraState.Error>().collect {
                if (_navigation.value.running) stopNavigation()
            }
        }
        // Keep the haptic policy config in sync with user settings.
        viewModelScope.launch {
            settings.collect { appSettings ->
                hapticPolicy.updateConfig(
                    HapticPolicyConfig(
                        confidenceThreshold = appSettings.confidenceThreshold,
                        perZoneCooldownMs = appSettings.cooldownMillis.toLong(),
                    )
                )
            }
        }
    }

    fun connectWearable() = bleController.connect()
    fun scanForBleDevices() = bleController.scanForDevices()
    fun connectToBleDevice(address: String) = bleController.connectToDevice(address)
    fun disconnectWearable() = bleController.disconnect()
    fun bluetoothPermissions(): Array<String> = bleController.requiredPermissions()
    fun hasBluetoothPermissions(): Boolean = bleController.hasRequiredPermissions()
    fun send(command: HapticCommand) { _navigation.value = _navigation.value.copy(latestCommand = command); bleController.send(command) }
    fun startCamera(context: android.content.Context, owner: androidx.lifecycle.LifecycleOwner, view: androidx.camera.view.PreviewView) =
        cameraController.start(context, owner, view, objectAnalyzer)
    fun startNavigation() {
        hapticPolicy.reset()
        _navigation.value = _navigation.value.copy(running = true, visionMessage = "ML Kit detector starting…", commandsSentCount = 0)
    }
    fun stopNavigation() = stopNavigationInternal("Detector stopped")

    private fun stopNavigationInternal(visionMessage: String) {
        cameraController.stop()
        hapticPolicy.reset()
        _navigation.value = _navigation.value.copy(
            running = false,
            latestCommand = HapticCommand.STOP,
            currentZone = DirectionZone.UNKNOWN,
            visionMessage = visionMessage,
            zoneCounts = emptyMap(),
        )
        if (ble.value.commandReady) bleController.send(HapticCommand.STOP)
    }
    fun saveSettings(settings: AppSettings) = viewModelScope.launch { settingsRepository.save(settings) }

    /** A backgrounded activity must not keep navigation pulses or BLE scanning active. */
    fun onHostStopped() {
        if (_navigation.value.running) stopNavigation()
        bleController.stopScan()
    }

    override fun onCleared() {
        stopNavigation()
        bleController.disconnect()
        objectAnalyzer.close()
        cameraController.close()
        super.onCleared()
    }

    private fun handleDetections(detections: List<DetectionResult>) {
        viewModelScope.launch {
            if (!_navigation.value.running) return@launch

            val now = SystemClock.elapsedRealtime()
            val result = hapticPolicy.evaluate(detections, now)

            _navigation.value = _navigation.value.copy(
                detections = detections,
                currentZone = result.activeZone,
                zoneCounts = result.zoneCounts,
                commandsSentCount = result.commandsSentCount,
                visionMessage = if (detections.isEmpty()) {
                    "No ML Kit object detected"
                } else {
                    "ML Kit processed ${detections.size} object(s)"
                },
            )

            val command = result.command ?: return@launch
            if (!ble.value.commandReady) {
                Log.d(TAG, "Command $command ready but BLE not connected, skipping")
                return@launch
            }

            _navigation.value = _navigation.value.copy(latestCommand = command)
            bleController.send(command)
        }
    }

    private fun handleVisionError(error: Exception) {
        viewModelScope.launch {
            if (_navigation.value.running) {
                stopNavigationInternal("ML Kit error: ${error.message ?: "unknown error"}")
            }
        }
    }

    companion object {
        private const val TAG = "SixthSenseViewModel"
    }
}
