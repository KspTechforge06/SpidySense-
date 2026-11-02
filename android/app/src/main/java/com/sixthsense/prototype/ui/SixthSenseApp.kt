package com.sixthsense.prototype.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sixthsense.prototype.bluetooth.BleConnectionState
import com.sixthsense.prototype.camera.CameraState
import com.sixthsense.prototype.navigation.HapticCommand
import com.sixthsense.prototype.settings.AppSettings
import com.sixthsense.prototype.viewmodel.SixthSenseViewModel
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

private object Destinations { const val HOME = "home"; const val MOTOR = "motor"; const val NAVIGATION = "navigation"; const val SETTINGS = "settings" }

@Composable
fun SixthSenseApp(viewModel: SixthSenseViewModel) {
    val nav = rememberNavController()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = Destinations.HOME) {
                composable(Destinations.HOME) { HomeScreen(viewModel, { nav.navigate(Destinations.NAVIGATION) }, { nav.navigate(Destinations.MOTOR) }, { nav.navigate(Destinations.SETTINGS) }) }
                composable(Destinations.MOTOR) { MotorTestScreen(viewModel, { nav.popBackStack() }) }
                composable(Destinations.NAVIGATION) { NavigationScreen(viewModel, { nav.popBackStack() }) }
                composable(Destinations.SETTINGS) { SettingsScreen(viewModel, { nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        @OptIn(ExperimentalMaterial3Api::class)
        TopAppBar(title = { Text(title) }, navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } })
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content(padding) } }
}

@Composable
private fun BleStatus(viewModel: SixthSenseViewModel) {
    val ble by viewModel.ble.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("BLE: ${ble.connectionState}", fontWeight = FontWeight.SemiBold)
        Text(ble.message)
    } }
}

@Composable
private fun HomeScreen(viewModel: SixthSenseViewModel, openNavigation: () -> Unit, openMotor: () -> Unit, openSettings: () -> Unit) {
    Page("SixthSense") {
        Text("Experimental assistive-technology prototype", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) { Text(
            "This is not a certified mobility aid and does not replace a cane, guide dog, or attentive travel practices. No detection never means a path is clear.",
            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error
        ) }
        BleStatus(viewModel)
        Button(onClick = openNavigation, modifier = Modifier.fillMaxWidth()) { Text("Navigation") }
        Button(onClick = openMotor, modifier = Modifier.fillMaxWidth()) { Text("Motor Test") }
        OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
    }
}

@Composable
private fun MotorTestScreen(viewModel: SixthSenseViewModel, onBack: () -> Unit) {
    val ble by viewModel.ble.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) viewModel.connectWearable()
    }
    Page("Motor Test", onBack) {
        BleStatus(viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (viewModel.hasBluetoothPermissions()) viewModel.connectWearable()
                else permissionLauncher.launch(viewModel.bluetoothPermissions())
            }, enabled = ble.connectionState != BleConnectionState.SCANNING && ble.connectionState != BleConnectionState.CONNECTING) { Text("Connect") }
            OutlinedButton(onClick = viewModel::disconnectWearable, enabled = ble.connectionState != BleConnectionState.DISCONNECTED) { Text("Disconnect") }
        }
        Text("Controls are enabled only after the expected GATT service and command characteristic are ready.")
        val ready = ble.commandReady
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.send(HapticCommand.LEFT) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Pulse Left") }
            Button(onClick = { viewModel.send(HapticCommand.FRONT) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Pulse Front") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.send(HapticCommand.RIGHT) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Pulse Right") }
            Button(onClick = { viewModel.send(HapticCommand.BACK) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Pulse Back") }
        }
        OutlinedButton(onClick = { viewModel.send(HapticCommand.ALL) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Pulse All") }
        Button(
            onClick = { viewModel.send(HapticCommand.STOP) }, enabled = ready,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        ) { Text("STOP ALL", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun NavigationScreen(viewModel: SixthSenseViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val camera by viewModel.camera.collectAsStateWithLifecycle()
    val ble by viewModel.ble.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { cameraLauncher.launch(Manifest.permission.CAMERA) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    Page("Navigation", onBack = { viewModel.stopNavigation(); onBack() }) {
        Card(Modifier.fillMaxWidth()) {
            AndroidView(
                factory = { PreviewView(it).also { view -> previewView = view } },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }
        when (camera) {
            CameraState.Idle -> Text("Camera stopped. Camera permission is required to start preview.")
            CameraState.Starting -> Text("Camera starting…")
            CameraState.Running -> Text("Rear camera preview and on-device ML Kit detection are running.")
            is CameraState.Error -> Text((camera as CameraState.Error).message, color = MaterialTheme.colorScheme.error)
        }
        BleStatus(viewModel)
        Text("Current zone: ${navigation.currentZone}")
        Text("Latest command: ${navigation.latestCommand ?: "—"}")
        Text(navigation.visionMessage)
        if (navigation.detections.isNotEmpty()) {
            Text(
                "Detected: " + navigation.detections.joinToString { detection ->
                    "${detection.label} ${(detection.confidence * 100).toInt()}%"
                },
            )
        }
        Text("No detection is not a clear-path indication.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    previewView?.let { viewModel.startCamera(context, owner, it) }
                    viewModel.startNavigation()
                } else cameraLauncher.launch(Manifest.permission.CAMERA)
            }, modifier = Modifier.weight(1f)) { Text("Start") }
            Button(onClick = viewModel::stopNavigation, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop") }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: SixthSenseViewModel, onBack: () -> Unit) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    var confidence by remember(stored) { mutableStateOf(stored.confidenceThreshold) }
    var cooldown by remember(stored) { mutableIntStateOf(stored.cooldownMillis) }
    var pulse by remember(stored) { mutableIntStateOf(stored.pulseDurationMillis) }
    var debugLabels by remember(stored) { mutableStateOf(stored.showDebugLabels) }
    Page("Settings", onBack) {
        Text("Local prototype tuning only. These settings do not make detection comprehensive or travel safe.", color = MaterialTheme.colorScheme.error)
        Text("Detection confidence: ${(confidence * 100).toInt()}%")
        Slider(value = confidence, onValueChange = { confidence = it }, valueRange = 0.1f..0.95f)
        Text("Haptic cooldown: ${cooldown} ms")
        Slider(value = cooldown.toFloat(), onValueChange = { cooldown = it.toInt() }, valueRange = 100f..5_000f)
        Text("Pulse duration: ${pulse} ms")
        Slider(value = pulse.toFloat(), onValueChange = { pulse = it.toInt() }, valueRange = 50f..1_000f)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show debug labels", Modifier.weight(1f)); Switch(checked = debugLabels, onCheckedChange = { debugLabels = it }) }
        Button(onClick = { viewModel.saveSettings(AppSettings(confidence, cooldown, pulse, debugLabels)); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Save local settings") }
    }
}
