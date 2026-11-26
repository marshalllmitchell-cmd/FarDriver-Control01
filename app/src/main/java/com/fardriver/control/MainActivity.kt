package com.fardriver.control

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val bg = Color(0xFF07121F)
private val card = Color(0xFF122337)
private val blue = Color(0xFF2A9DF4)
private val white = Color(0xFFF2F7FF)
class MainActivity: ComponentActivity() {
    private var ble: BleManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("Disconnected") }
            var received by remember { mutableIntStateOf(0) }
            var lastFrame by remember { mutableStateOf("No frames received") }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants.values.all { it }) ble?.scan() else status = "Bluetooth permissions denied"
            }
            LaunchedEffect(Unit) {
                ble = BleManager(this@MainActivity, { runOnUiThread { status = it } }, { frame ->
                    runOnUiThread { received++; lastFrame = frame.joinToString(" ") { "%02X".format(it) } }
                })
            }
            FarDriverUi(status, received, lastFrame, onScan = {
                val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) ble?.scan() else permissionLauncher.launch(missing.toTypedArray())
            })
        }
    }
    override fun onDestroy() { ble?.close(); super.onDestroy() }
}
@Composable private fun FarDriverUi(status: String, received: Int, lastFrame: String, onScan: ()->Unit) {
    var page by remember { mutableStateOf("Ride") }
    var menuOpen by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    val pages = listOf("Ride", "Tuning", "Diagnostics", "My Setup")
    MaterialTheme(colorScheme = darkColorScheme(primary = blue, background = bg, surface = card, onSurface = white)) {
        Column(Modifier.fillMaxSize().background(bg).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wide) TextButton(onClick = { menuOpen = !menuOpen }) { Text("☰", fontSize = 24.sp) }
                Text("FARDRIVER CONTROL", color = white, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                Text(status.take(42), color = if (status.startsWith("Connected")) Color(0xFF62DDA1) else Color(0xFFFFCB68), fontSize = 12.sp)
            }
            Row(Modifier.weight(1f)) {
                if (wide && menuOpen) Column(Modifier.width(160.dp).verticalScroll(rememberScrollState())) {
                    pages.forEach { p -> TextButton(onClick = { page = p; menuOpen = false }) { Text(p) } }
                }
                Column(Modifier.weight(1f)) {
                    when(page) {
                        "Ride" -> RideScreen(received)
                        "Tuning" -> TuningScreen()
                        "Diagnostics" -> DiagnosticsScreen(received, lastFrame, onScan)
                        else -> SetupScreen()
                    }
                }
            }
            if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pages.forEach { p -> TextButton(onClick = { page = p }) { Text(p, fontSize = 12.sp, color = if (page == p) blue else white) } }
            }
        }
    }
}
@Composable private fun Panel(title: String, content: @Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(card, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(title, color = blue, fontSize = 18.sp); Spacer(Modifier.height(8.dp)); content()
    }
}
@Composable private fun RideScreen(received: Int) {
    LazyColumn { item {
        Text("RIDE DASHBOARD", color = white, fontSize = 22.sp)
        Panel("Speed") { Text("— MPH", fontSize = 40.sp, color = white); Text("Awaiting validated telemetry decoder") }
        Panel("Electrical") { Text("Battery volts: — V    Battery amps: — A", color = white); Text("Phase A: — A    Phase C: — A", color = white) }
        Panel("Status") { Text("Throttle: unknown    Speed mode: unknown", color = white); Text("Active faults: unknown", color = white) }
        Panel("Temperatures") { Text("Controller: — °F (— °C)", color = white); Text("Motor: — °F (— °C)", color = white) }
        Text("BLE notification callbacks: $received. Values remain blank until decoding is validated.", color = Color.LightGray)
    } }
}
@Composable private fun TuningScreen() {
    val names = listOf("Battery current limit" to "A", "Phase current limit" to "A", "Speed mode 1" to "%", "Speed mode 2" to "%", "Speed mode 3" to "%", "Regen strength" to "%")
    LazyColumn { item { Text("TUNING • OFFLINE STAGING", color = white, fontSize = 22.sp)
        Text("Illustrative UI controls only. No controller writes or assumed safe ranges.", color = Color(0xFFFFCB68)) }
        items(names) { (name, unit) -> var value by remember(name) { mutableFloatStateOf(50f) }
            Panel(name) { Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                Text(" ${value.roundToInt()} $unit", color = white)
            }; Text("Uncalibrated placeholder scale, not a hardware limit", color = Color.LightGray, fontSize = 11.sp) }
        }
    }
}
@Composable private fun DiagnosticsScreen(received: Int, lastFrame: String, onScan: ()->Unit) {
    var recording by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    LazyColumn { item {
        Text("DIAGNOSTICS & ADVANCED MONITORING", color = white, fontSize = 21.sp)
        Panel("Bluetooth") { Button(onClick = onScan) { Text("Scan & Connect") }; Text("Notifications received: $received", color = white)
            Text("Last raw notification: $lastFrame", color = Color.LightGray, fontSize = 11.sp) }
        Panel("Fault history") { Text("No decoded fault history available yet", color = white) }
        Panel("Potential safety concerns") { Text("Complete My Setup and validate controller data before evaluating limits.", color = white) }
        Panel("Data logger") { Text("Raw notification capture (in-memory prototype only)", color = white)
            Row { Button(onClick = { recording = !recording }) { Text(if (recording) "Stop" else "Start") }
                Spacer(Modifier.width(8.dp)); Button(onClick = { logs = emptyList() }) { Text("Clear") } }
            if (recording) Text("Recording UI armed. Persistent time-series logging not implemented yet.", color = Color(0xFFFFCB68))
            Text("Captured entries: ${logs.size}", color = white) }
        Panel("Before / after comparison") { Text("Requires saved rides and validated telemetry", color = white) }
    } }
}
@Composable private fun SetupScreen() {
    var vehicle by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf("ND72530") }
    var battery by remember { mutableStateOf("") }
    var bms by remember { mutableStateOf("") }
    var motor by remember { mutableStateOf("") }
    var limits by remember { mutableStateOf("") }
    val fields = listOf("Vehicle name" to vehicle, "Controller model" to controller, "Battery specification" to battery, "BMS specification" to bms, "Motor specification" to motor, "Verified limits / notes" to limits)
    LazyColumn { item { Text("MY SETUP", color = white, fontSize = 22.sp)
        Text("Local editing prototype; values are not saved across app restarts yet.", color = Color(0xFFFFCB68)) }
        items(fields) { (label, value) -> Panel(label) {
            OutlinedTextField(value = value, onValueChange = { when(label) {
                "Vehicle name" -> vehicle = it; "Controller model" -> controller = it; "Battery specification" -> battery = it
                "BMS specification" -> bms = it; "Motor specification" -> motor = it; else -> limits = it
            } }, modifier = Modifier.fillMaxWidth(), singleLine = label != "Verified limits / notes", label = { Text(label) })
        } }
    }
}
