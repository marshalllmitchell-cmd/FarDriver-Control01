package com.fardriver.control

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

private val navy = Color(0xFF081522)
private val panel = Color(0xFF14283D)
private val accent = Color(0xFF38A9FA)

class MainActivity : ComponentActivity() {
    private var ble: BleManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("Disconnected") }
            var frameCount by remember { mutableIntStateOf(0) }
            var lastFrame by remember { mutableStateOf("No notifications yet") }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                if (result.values.all { it }) {
                    try { ble?.scan() } catch (e: Exception) { status = "Scan error: ${e.message}" }
                } else status = "Bluetooth permissions denied"
            }
            val startScan: () -> Unit = {
                try {
                    if (ble == null) {
                        ble = BleManager(this@MainActivity,
                            { message -> runOnUiThread { status = message } },
                            { data -> runOnUiThread {
                                frameCount++
                                lastFrame = data.joinToString(" ") { "%02X".format(it.toInt() and 255) }
                            } })
                    }
                    val required = if (Build.VERSION.SDK_INT >= 31) {
                        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) ble?.scan() else launcher.launch(missing.toTypedArray())
                } catch (e: Exception) { status = "Bluetooth unavailable: ${e.message}" }
            }
            AppUi(status, frameCount, lastFrame, startScan)
        }
    }

    override fun onDestroy() {
        try { ble?.close() } catch (_: Exception) { }
        super.onDestroy()
    }
}

@Composable
private fun AppUi(status: String, frames: Int, lastFrame: String, scan: () -> Unit) {
    var page by remember { mutableStateOf("Ride") }
    var menu by remember { mutableStateOf(false) }
    val pages = listOf("Ride", "Tuning", "Diagnostics", "My Setup")
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = navy, surface = panel)) {
        Column(Modifier.fillMaxSize().background(navy).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wide) TextButton(onClick = { menu = !menu }) { Text("Menu") }
                Text("FARDRIVER CONTROL", color = Color.White, fontSize = 19.sp)
                Spacer(Modifier.weight(1f))
                Text(status.take(30), color = accent, fontSize = 11.sp)
            }
            Row(Modifier.weight(1f)) {
                if (wide && menu) Column(Modifier.width(150.dp)) {
                    pages.forEach { item -> TextButton(onClick = { page = item; menu = false }) { Text(item) } }
                }
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text(page.uppercase(), color = Color.White, fontSize = 22.sp)
                        when (page) {
                            "Ride" -> RideDashboard(status)
                            "Tuning" -> {
                                Text("Offline UI demonstration. Sliders are NOT controller settings.", color = Color(0xFFFFD17A))
                                listOf("Battery current", "Phase current", "Speed mode 1", "Speed mode 2", "Speed mode 3", "Regeneration").forEach { label ->
                                    var value by remember(label) { mutableFloatStateOf(50f) }
                                    Section(label) {
                                        Slider(value = value, onValueChange = { value = it })
                                        Text("Illustrative position: ${value.toInt()} / 100")
                                    }
                                }
                            }
                            "Diagnostics" -> {
                                Section("Bluetooth") {
                                    Button(onClick = scan) { Text("Scan & Connect") }
                                    Text("Notifications: $frames\nLast raw frame: $lastFrame", fontSize = 12.sp)
                                }
                                Section("Fault history") { Text("Decoder not yet validated") }
                                Section("Safety concerns") { Text("Requires verified My Setup limits and live data") }
                                Section("Data logger") { Text("Recording and ride comparison are not implemented yet") }
                            }
                            "My Setup" -> {
                                Text("Temporary form; data is not persisted yet.", color = Color(0xFFFFD17A))
                                listOf("Vehicle", "Controller", "Battery", "BMS", "Motor", "Verified limits").forEach { label ->
                                    var value by remember(label) { mutableStateOf(if (label == "Controller") "ND72530" else "") }
                                    Section(label) {
                                        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pages.forEach { item -> TextButton(onClick = { page = item }) { Text(item, fontSize = 12.sp) } }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(title, color = accent, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}


/** Read-only dashboard: placeholders until telemetry and fault decoders are verified. */
@Composable
private fun RideDashboard(connection: String) {
    val connected = connection.startsWith("Connected", ignoreCase = true)
    Text("LIVE RIDE MONITOR", color = Color(0xFF9CB5CD), fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).background(panel, RoundedCornerShape(14.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SPEED", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text("—", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Bold)
            Text("MPH", color = Color.White, fontSize = 20.sp)
            Text("— km/h", color = Color(0xFF9CB5CD), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.weight(1f).background(panel, RoundedCornerShape(14.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MOTOR RPM", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(158.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(140.dp)) {
                    val stroke = 11.dp.toPx()
                    drawArc(color = Color(0xFF263C52), startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(stroke))
                    // Unlit arc is intentional: no controller RPM has been decoded.
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("—", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    Text("RPM", color = Color(0xFF9CB5CD), fontSize = 12.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("ELECTRICAL", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RideMetric("BATTERY", "—", "V", Modifier.weight(1f))
        RideMetric("BATTERY DRAW", "—", "A", Modifier.weight(1f))
        RideMetric("POWER", "—", "kW", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RideMetric("PHASE A", "—", "A", Modifier.weight(1f))
        RideMetric("PHASE C", "—", "A", Modifier.weight(1f))
    }
    Section("DRIVE STATUS") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Throttle: unavailable", color = Color.White, fontSize = 13.sp)
            Text("Mode: unknown", color = Color.White, fontSize = 13.sp)
        }
        Text("Direction: unknown", color = Color(0xFFB9CBDB), fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TemperatureTile("CONTROLLER", Modifier.weight(1f))
        TemperatureTile("MOTOR", Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFFE3A74C), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("FAULT STATUS: NOT AVAILABLE", color = Color(0xFFFFCF80), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("No validated fault data. Do not assume the controller is fault-free.", color = Color.White, fontSize = 12.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(if (connected) "BLE connected. Telemetry decoding pending validation." else "DISCONNECTED • Live measurements unavailable", color = Color(0xFFFFCF80), fontSize = 12.sp)
}

@Composable
private fun RideMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp).background(panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(label, color = Color(0xFF9EC7E8), fontSize = 10.sp, maxLines = 2)
        Spacer(Modifier.height(7.dp))
        Text("$value $unit", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TemperatureTile(label: String, modifier: Modifier = Modifier) {
    Column(modifier.background(panel, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text("$label TEMP", color = accent, fontSize = 11.sp)
        Text("— °F (— °C)", color = Color.White, fontSize = 16.sp)
    }
}
