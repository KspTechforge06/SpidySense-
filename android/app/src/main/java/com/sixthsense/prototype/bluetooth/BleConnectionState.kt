package com.sixthsense.prototype.bluetooth

enum class BleConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, DISCOVERING_SERVICES, CONNECTED, ERROR
}

data class BleUiState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val message: String = "Wearable disconnected",
    val commandReady: Boolean = false,
)
