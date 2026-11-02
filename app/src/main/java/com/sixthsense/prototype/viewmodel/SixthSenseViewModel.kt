package com.sixthsense.prototype.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sixthsense.prototype.bluetooth.BleController
import com.sixthsense.prototype.bluetooth.BleUiState
import com.sixthsense.prototype.camera.CameraController
import com.sixthsense.prototype.camera.CameraState
import com.sixthsense.prototype.navigation.HapticCommand
import com.sixthsense.prototype.settings.AppSettings
import com.sixthsense.prototype.settings.SettingsRepository
import com.sixthsense.prototype.vision.DirectionZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NavigationUiState(
    val running: Boolean = false,
    val currentZone: DirectionZone = DirectionZone.UNKNOWN,
    val latestCommand: HapticCommand? = null,
)

class SixthSenseViewModel(application: Application) : AndroidViewModel(application) {
    private val bleController = BleController(application)
    private val settingsRepository = SettingsRepository(application)
    private val cameraController = CameraController()
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
        cameraController.start(context, owner, view)
    fun startNavigation() { _navigation.value = _navigation.value.copy(running = true) }
    fun stopNavigation() {
        cameraController.stop()
        _navigation.value = _navigation.value.copy(running = false, latestCommand = HapticCommand.STOP)
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
        super.onCleared()
    }
}
