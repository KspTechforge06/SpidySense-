package com.sixthsense.prototype.bluetooth

enum class BleConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, DISCOVERING_SERVICES, CONNECTED, ERROR
}

data class BleUiState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val message: String = "Wearable disconnected",
    val commandReady: Boolean = false,
    val discoveredDevices: List<BleDiscoveredDevice> = emptyList(),
)

/** A nearby BLE advertiser shown in the manual connection picker. */
data class BleDiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)
