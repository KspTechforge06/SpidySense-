package com.sixthsense.prototype.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sixthsense.prototype.navigation.HapticCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Android BLE work is isolated here; UI observes [state] and never touches GATT. */
class BleController(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var writeInFlight = false
    private var intentionalDisconnect = false

    private val scanTimeout = Runnable {
        if (_state.value.connectionState == BleConnectionState.SCANNING) {
            stopScan()
            error("SixthSense-ESP32 was not found before scan timeout.")
        }
    }

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!hasRequiredPermissions()) { error("Bluetooth permission is required to scan and connect."); return }
        if (adapter?.isEnabled != true) { error("Bluetooth is unavailable or turned off."); return }
        closeConnection()
        _state.value = BleUiState(BleConnectionState.SCANNING, "Scanning for ${BleProtocol.DEVICE_NAME}…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isSixthSenseWearable(result)) return
                stopScan()
                _state.value = BleUiState(
                    BleConnectionState.CONNECTING,
                    "Found ${BleProtocol.DEVICE_NAME}; connecting…",
                )
                connectGatt(result.device)
            }
            override fun onScanFailed(errorCode: Int) = error("BLE scan failed (code $errorCode).")
        }
        scannerCallback = callback
        // Do not use Android's hardware service-UUID filter here. Some ESP32
        // stacks put a 128-bit UUID or the local name in scan-response data,
        // which can be ignored by that filter even though a normal BLE scanner
        // sees the peripheral. Match in the callback instead.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // The settings-only overload does not exist. A null filter list means
        // "report every BLE advertiser", then isSixthSenseWearable filters it.
        adapter?.bluetoothLeScanner?.startScan(null, settings, callback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        _state.value = BleUiState(BleConnectionState.CONNECTING, "Connecting to ${BleProtocol.DEVICE_NAME}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleUiState(BleConnectionState.DISCOVERING_SERVICES, "Discovering wearable services…")
                if (!gatt.discoverServices()) error("Could not begin BLE service discovery.")
            } else {
                commandCharacteristic = null
                writeInFlight = false
                closeGatt()
                if (intentionalDisconnect) {
                    intentionalDisconnect = false
                    _state.value = BleUiState(BleConnectionState.DISCONNECTED, "Wearable disconnected")
                } else {
                    error("Wearable disconnected (GATT status $status).")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { error("BLE service discovery failed (status $status)."); return }
            val service = gatt.getService(UUID.fromString(BleProtocol.SERVICE_UUID))
            val command = service?.getCharacteristic(UUID.fromString(BleProtocol.COMMAND_UUID))
            if (command == null) { error("Wearable service or command characteristic is missing."); return }
            commandCharacteristic = command
            service.getCharacteristic(UUID.fromString(BleProtocol.STATUS_UUID))?.let { statusCharacteristic ->
                gatt.setCharacteristicNotification(statusCharacteristic, true)
                statusCharacteristic.descriptors.firstOrNull { it.uuid == CCCD_UUID }?.let { descriptor ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
            _state.value = BleUiState(BleConnectionState.CONNECTED, "Wearable ready for commands.", commandReady = true)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) error("Command write failed (GATT status $status).")
            else _state.value = _state.value.copy(message = "Command sent.")
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) = handleStatus(characteristic.value)

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleStatus(value)
    }

    private fun handleStatus(value: ByteArray) {
        val status = String(value, StandardCharsets.US_ASCII).trim()
        if (status.isNotEmpty()) {
            _state.value = _state.value.copy(message = "Wearable: $status")
        }
    }

    private fun isSixthSenseWearable(result: ScanResult): Boolean {
        val record = result.scanRecord
        val advertisedName = record?.deviceName
        val serviceUuid = UUID.fromString(BleProtocol.SERVICE_UUID)
        val hasExpectedService = record?.serviceUuids?.any { it.uuid == serviceUuid } == true
        return result.device.name == BleProtocol.DEVICE_NAME ||
            advertisedName == BleProtocol.DEVICE_NAME ||
            hasExpectedService
    }

    @SuppressLint("MissingPermission")
    fun send(command: HapticCommand) {
        val currentGatt = gatt
        val characteristic = commandCharacteristic
        if (!hasRequiredPermissions()) { error("Bluetooth permission is missing."); return }
        if (_state.value.connectionState != BleConnectionState.CONNECTED || currentGatt == null || characteristic == null) {
            error("Wearable is not ready for commands."); return
        }
        if (writeInFlight) {
            _state.value = _state.value.copy(message = "Previous command is still sending; new command dropped.")
            return
        }
        writeInFlight = true
        val bytes = BleProtocol.encode(command)
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) { writeInFlight = false; error("BLE did not accept the command write.") }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(sendStop: Boolean = true) {
        stopScan()
        if (sendStop && _state.value.commandReady) {
            // Give the immediate-stop write one short GATT interval before closing the link.
            send(HapticCommand.STOP)
            handler.postDelayed(::closeConnection, STOP_BEFORE_CLOSE_MS)
        } else closeConnection()
    }

    @SuppressLint("MissingPermission")
    private fun closeConnection() {
        intentionalDisconnect = true
        commandCharacteristic = null
        writeInFlight = false
        gatt?.disconnect()
        closeGatt()
        _state.value = BleUiState(BleConnectionState.DISCONNECTED, "Wearable disconnected")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scannerCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scannerCallback = null
        if (_state.value.connectionState == BleConnectionState.SCANNING) {
            _state.value = BleUiState(BleConnectionState.DISCONNECTED, "BLE scan stopped.")
        }
    }

    private fun closeGatt() { gatt?.close(); gatt = null }
    private fun error(message: String) { _state.value = BleUiState(BleConnectionState.ERROR, message) }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val STOP_BEFORE_CLOSE_MS = 150L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
