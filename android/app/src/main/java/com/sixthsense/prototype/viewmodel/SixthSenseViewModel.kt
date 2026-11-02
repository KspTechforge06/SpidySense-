package com.sixthsense.prototype.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sixthsense.prototype.bluetooth.BleController
import com.sixthsense.prototype.bluetooth.BleUiState
import com.sixthsense.prototype.camera.CameraController
import com.sixthsense.prototype.camera.CameraState
import com.sixthsense.prototype.navigation.HapticCommand
import com.sixthsense.prototype.navigation.HapticCommandMapper
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
)

class SixthSenseViewModel(application: Application) : AndroidViewModel(application) {
    private val bleController = BleController(application)
    private val settingsRepository = SettingsRepository(application)
    private val cameraController = CameraController()
    private val objectAnalyzer = MlKitObjectAnalyzer(
        onDetections = ::handleDetections,
        onError = { error -> handleVisionError(error) },
    )
    private var lastHapticAtMs = Long.MIN_VALUE
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
    }

    fun connectWearable() = bleController.connect()
    fun disconnectWearable() = bleController.disconnect()
    fun bluetoothPermissions(): Array<String> = bleController.requiredPermissions()
    fun hasBluetoothPermissions(): Boolean = bleController.hasRequiredPermissions()
    fun send(command: HapticCommand) { _navigation.value = _navigation.value.copy(latestCommand = command); bleController.send(command) }
    fun startCamera(context: android.content.Context, owner: androidx.lifecycle.LifecycleOwner, view: androidx.camera.view.PreviewView) =
        cameraController.start(context, owner, view, objectAnalyzer)
    fun startNavigation() {
        _navigation.value = _navigation.value.copy(running = true, visionMessage = "ML Kit detector starting…")
    }
    fun stopNavigation() = stopNavigationInternal("Detector stopped")

    private fun stopNavigationInternal(visionMessage: String) {
        cameraController.stop()
        _navigation.value = _navigation.value.copy(
            running = false,
            latestCommand = HapticCommand.STOP,
            currentZone = DirectionZone.UNKNOWN,
            visionMessage = visionMessage,
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

            val configuration = settings.value
            // ML Kit's default classifier is coarse. Until a custom detector is
            // selected, trigger only from a classification above the user's
            // threshold; unclassified boxes remain visible for debugging.
            val candidate = detections
                .filter { it.confidence >= configuration.confidenceThreshold }
                .maxByOrNull { it.confidence }
            val zone = candidate?.let {
                DirectionZoneMapper.fromNormalizedCenter(it.normalizedHorizontalCenter)
            } ?: DirectionZone.UNKNOWN
            _navigation.value = _navigation.value.copy(
                detections = detections,
                currentZone = zone,
                visionMessage = if (detections.isEmpty()) "No ML Kit object detected" else "ML Kit processed ${detections.size} object(s)",
            )

            val command = candidate?.let { HapticCommandMapper.forZone(zone) } ?: return@launch
            val now = SystemClock.elapsedRealtime()
            if (!ble.value.commandReady) return@launch
            if (lastHapticAtMs != Long.MIN_VALUE && now - lastHapticAtMs < configuration.cooldownMillis) {
                return@launch
            }

            lastHapticAtMs = now
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
}
