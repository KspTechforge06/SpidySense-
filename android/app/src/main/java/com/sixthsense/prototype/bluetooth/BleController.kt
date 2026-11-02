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
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var lastWriteAtMs = 0L
    private var intentionalDisconnect = false
    private val scannedDevices = mutableMapOf<String, BluetoothDevice>()

    private val scanTimeout = Runnable {
        if (_state.value.connectionState == BleConnectionState.SCANNING) {
            stopScan(preserveMessage = true)
            _state.value = _state.value.copy(
                connectionState = BleConnectionState.DISCONNECTED,
                message = if (_state.value.discoveredDevices.isEmpty()) {
                    "No BLE devices were found. Check Nearby devices permission and Bluetooth."
                } else {
                    "Scan finished. Select a device from the list to connect."
                },
            )
        }
    }

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    /**
     * On Android 11 and earlier, BLE scanning requires Location Services
     * (GPS / network location) to be turned on in system settings, even when
     * the app already holds ACCESS_FINE_LOCATION permission.
     * Android 12+ with `neverForLocation` does NOT require this.
     */
    private fun isLocationEnabledIfRequired(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        return try {
            val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (_: Exception) { true }
    }

    /** Public accessor for the UI to show a helpful message before the user taps Scan. */
    fun needsLocationServicesEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabledIfRequired()

    @SuppressLint("MissingPermission")
    fun scanForDevices() {
        if (!hasRequiredPermissions()) { error("Bluetooth permission is required to scan and connect."); return }
        if (adapter?.isEnabled != true) { error("Bluetooth is unavailable or turned off."); return }
        if (!isLocationEnabledIfRequired()) {
            error("Location services must be turned on for BLE scanning (Android 11 and below). Enable GPS or network location in system Settings.")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            error("BLE scanner is not available. Check that Bluetooth is on and try again.")
            return
        }
        closeConnection()
        scannedDevices.clear()
        _state.value = BleUiState(BleConnectionState.SCANNING, "Scanning for nearby BLE devices…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "onScanResult: ${result.device.address} name=${result.scanRecord?.deviceName} rssi=${result.rssi}")
                addDiscoveredDevice(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "onBatchScanResults: ${results.size} results")
                results.forEach { addDiscoveredDevice(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code $errorCode")
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> {
                        // Stop existing scan and retry once.
                        scannerCallback?.let { scanner.stopScan(it) }
                        scannerCallback = null
                        handler.postDelayed({ scanForDevices() }, 500)
                        return
                    }
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        error("BLE scan failed: app registration failed. Toggle Bluetooth off/on and try again.")
                    SCAN_FAILED_FEATURE_UNSUPPORTED ->
                        error("BLE scan failed: feature unsupported on this device.")
                    else -> error("BLE scan failed (code $errorCode). Try toggling Bluetooth off and on.")
                }
            }
        }
        scannerCallback = callback
        // Do not use Android's hardware service-UUID filter here. Some ESP32
        // stacks put a 128-bit UUID or the local name in scan-response data,
        // which can be ignored by that filter even though a normal BLE scanner
        // sees the peripheral. Show the complete result list instead.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)  // Deliver results immediately, not batched
            .build()
        // The settings-only overload does not exist. A null filter list means
        // "report every BLE advertiser".
        scanner.startScan(null, settings, callback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** Kept for callers that previously used the automatic connect action. */
    fun connect() = scanForDevices()

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (!hasRequiredPermissions()) { error("Bluetooth permission is required to connect."); return }
        val device = scannedDevices[address]
            ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { error("That device is no longer available. Scan again."); return }
        stopScan(preserveMessage = true)
        connectGatt(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        _state.value = BleUiState(BleConnectionState.CONNECTING, "Connecting to ${BleProtocol.DEVICE_NAME}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else device.connectGatt(appContext, false, gattCallback)
    }

    private fun refreshGatt(gatt: BluetoothGatt): Boolean {
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val refreshed = refreshMethod.invoke(gatt) as? Boolean ?: false
            Log.d(TAG, "GATT cache refresh result: $refreshed")
            refreshed
        } catch (e: Exception) {
            Log.w(TAG, "GATT refresh not supported: ${e.message}")
            false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleUiState(BleConnectionState.DISCOVERING_SERVICES, "Discovering wearable services…")
                // Flush stale GATT attribute handle cache before discovering services
                refreshGatt(gatt)
                handler.postDelayed({
                    if (!gatt.discoverServices()) error("Could not begin BLE service discovery.")
                }, 250)
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

            var descriptorPending = false
            service.getCharacteristic(UUID.fromString(BleProtocol.STATUS_UUID))?.let { statusCharacteristic ->
                gatt.setCharacteristicNotification(statusCharacteristic, true)
                statusCharacteristic.descriptors.firstOrNull { it.uuid == CCCD_UUID }?.let { descriptor ->
                    descriptorPending = true
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
            if (!descriptorPending) {
                _state.value = BleUiState(BleConnectionState.CONNECTED, "Wearable ready for commands.", commandReady = true)
            } else {
                _state.value = BleUiState(BleConnectionState.CONNECTING, "Subscribing to wearable status…")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "CCCD onDescriptorWrite status: $status")
            _state.value = BleUiState(BleConnectionState.CONNECTED, "Wearable ready for commands.", commandReady = true)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Command write status warning: $status")
                _state.value = _state.value.copy(message = "Command write warning (status $status).")
            } else {
                _state.value = _state.value.copy(message = "Command sent.")
            }
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

    @SuppressLint("MissingPermission")
    private fun addDiscoveredDevice(result: ScanResult) {
        val device = result.device
        val name = result.scanRecord?.deviceName ?: device.name ?: "Unnamed BLE device"
        scannedDevices[device.address] = device
        val updated = _state.value.discoveredDevices
            .filterNot { it.address == device.address }
            .plus(BleDiscoveredDevice(device.address, name, result.rssi))
            .sortedWith(compareByDescending<BleDiscoveredDevice> { it.name == BleProtocol.DEVICE_NAME }.thenByDescending { it.rssi })
        _state.value = _state.value.copy(
            message = "Scanning… ${updated.size} nearby device(s) found.",
            discoveredDevices = updated,
        )
    }

    @SuppressLint("MissingPermission")
    fun send(command: HapticCommand) {
        val currentGatt = gatt
        val characteristic = commandCharacteristic
        if (!hasRequiredPermissions()) { error("Bluetooth permission is missing."); return }
        if (_state.value.connectionState != BleConnectionState.CONNECTED || currentGatt == null || characteristic == null) {
            error("Wearable is not ready for commands."); return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (writeInFlight && now - lastWriteAtMs < 500) {
            Log.d(TAG, "Previous command still in flight (${now - lastWriteAtMs}ms), dropping repeat")
            return
        }
        writeInFlight = true
        lastWriteAtMs = now
        val bytes = BleProtocol.encode(command)
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            writeInFlight = false
            Log.w(TAG, "writeCharacteristic returned false/busy")
            _state.value = _state.value.copy(message = "BLE pipe busy; retry command.")
        }
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
    fun stopScan(preserveMessage: Boolean = false) {
        handler.removeCallbacks(scanTimeout)
        scannerCallback?.let { cb ->
            try { adapter?.bluetoothLeScanner?.stopScan(cb) }
            catch (e: Exception) { Log.w(TAG, "stopScan error: ${e.message}") }
        }
        scannerCallback = null
        if (!preserveMessage && _state.value.connectionState == BleConnectionState.SCANNING) {
            _state.value = BleUiState(BleConnectionState.DISCONNECTED, "BLE scan stopped.")
        }
    }

    private fun closeGatt() { gatt?.close(); gatt = null }
    private fun error(message: String) { _state.value = BleUiState(BleConnectionState.ERROR, message) }

    companion object {
        private const val TAG = "BleController"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val STOP_BEFORE_CLOSE_MS = 150L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
