package com.sixthsense.prototype.bluetooth

import com.sixthsense.prototype.navigation.HapticCommand

object BleProtocol {
    const val DEVICE_NAME = "SixthSense-ESP32"
    const val SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
    const val COMMAND_UUID = "12345678-1234-1234-1234-1234567890ac"
    const val STATUS_UUID = "12345678-1234-1234-1234-1234567890ad"

    fun encode(command: HapticCommand): ByteArray = byteArrayOf(
        when (command) {
            HapticCommand.LEFT -> 'L'
            HapticCommand.CENTER -> 'C'
            HapticCommand.RIGHT -> 'R'
            HapticCommand.ALL -> 'A'
            HapticCommand.STOP -> 'S'
        }.code.toByte()
    )
}
