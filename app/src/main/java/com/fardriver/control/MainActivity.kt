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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

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
                if (wide) TextButton(onClick = { menu = !menu }) { Text("☰", fontSize = 23.sp) }
                Text(buildAnnotatedString { withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append("FARDRIVER") }; withStyle(SpanStyle(color = Color.White)) { append(" CONTROL") } }, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(if (status.startsWith("Connected", true)) "● Connected" else "● Disconnected", color = if (status.startsWith("Connected", true)) Color(0xFF4CE2AA) else Color(0xFFFFC66E), fontSize = 11.sp)
            }
            Row(Modifier.weight(1f)) {
                if (wide && menu) Column(Modifier.width(150.dp)) {
                    pages.forEach { item -> TextButton(onClick = { page = item; menu = false }) { Text(item) } }
                }
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        if (page != "Ride") Text(page.uppercase(), color = Color.White, fontSize = 22.sp)
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
                pages.forEach { item -> TextButton(onClick = { page = item }) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(when(item) { "Ride" -> "⌂"; "Tuning" -> "☷"; "Diagnostics" -> "▤"; else -> "⚙" }, fontSize = 22.sp, color = if (page == item) accent else Color(0xFF86A5BE)); Text(item, fontSize = 11.sp, color = if (page == item) accent else Color(0xFFB4C7D7)) } } }
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


private val electricBlue = Color(0xFF20AFFF)
private val dimBlue = Color(0xFF557994)
private val amber = Color(0xFFFFB84E)

@Composable
private fun RideDashboard(connection: String) {
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    val connected = connection.startsWith("Connected", true)
    Text("RIDE  •  LIVE MONITOR", color = Color(0xFF9CBBD3), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.8f)) {
                GaugePair(Modifier.fillMaxWidth(), 220.dp)
                ElectricalCluster()
            }
            Column(Modifier.weight(1f)) {
                DriveCluster()
                TemperatureCluster()
                FaultCluster()
            }
        }
    } else {
        GaugePair(Modifier.fillMaxWidth(), 188.dp)
        Spacer(Modifier.height(8.dp))
        ElectricalCluster()
        DriveCluster()
        TemperatureCluster()
        FaultCluster()
    }
    Spacer(Modifier.height(6.dp))
    Text(if (connected) "Connected • Awaiting verified telemetry decoding" else "Disconnected • Readings unavailable", color = amber, fontSize = 10.sp)
}

@Composable
private fun GaugePair(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GaugeCard("SPEED", "MPH", "— km/h", electricBlue, false, Modifier.weight(1f).height(height))
        GaugeCard("MOTOR RPM", "RPM", "×1000", Color(0xFF80A9CA), true, Modifier.weight(1f).height(height))
    }
}

@Composable
private fun GaugeCard(label: String, unit: String, secondary: String, ring: Color, tach: Boolean, modifier: Modifier) {
    Column(modifier.background(Brush.verticalGradient(listOf(Color(0xFF142C43), Color(0xFF071A2B))), RoundedCornerShape(15.dp))
        .border(1.dp, Color(0xFF224D6A), RoundedCornerShape(15.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = electricBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val diameter = size.minDimension
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val width = 8.dp.toPx()
                drawArc(Color(0xFF243F56), 140f, 260f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(width, cap = StrokeCap.Round))
                drawArc(ring.copy(alpha = .78f), 140f, if (tach) 200f else 260f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                if (tach) drawArc(Color(0xFFF05A5A), 340f, 60f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                val cx = size.width / 2f; val cy = size.height / 2f
                for (i in 0..12) {
                    val angle = Math.toRadians((140.0 + i * 260.0 / 12.0))
                    val inner = diameter * .38f
                    val outer = diameter * .43f
                    drawLine(Color(0xFFB2D6EF), Offset(cx + (inner * cos(angle)).toFloat(), cy + (inner * sin(angle)).toFloat()), Offset(cx + (outer * cos(angle)).toFloat(), cy + (outer * sin(angle)).toFloat()), 1.2.dp.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("—", color = Color.White, fontSize = 47.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp)
                Text(unit, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(secondary, color = Color(0xFF9CBBD3), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ElectricalCluster() {
    Text("ELECTRICAL", color = electricBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ElectricTile("▰", "Battery", "V", Modifier.weight(1f))
        ElectricTile("⚡", "Battery Current", "A", Modifier.weight(1f))
        ElectricTile("◧", "Power", "kW", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ElectricTile("∿", "Phase A", "A", Modifier.weight(1f))
        ElectricTile("∿", "Phase C", "A", Modifier.weight(1f))
    }
}

@Composable
private fun ElectricTile(icon: String, label: String, unit: String, modifier: Modifier) {
    Column(modifier.padding(top = 6.dp).background(Color(0xFF0D263D), RoundedCornerShape(10.dp))
        .border(1.dp, Color(0xFF185278), RoundedCornerShape(10.dp)).padding(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = electricBlue, fontSize = 16.sp)
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color(0xFFB9D4E9), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text("— $unit", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DriveCluster() {
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(Color(0xFF0D263D), RoundedCornerShape(12.dp))
        .border(1.dp, Color(0xFF185278), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("DRIVE STATUS", color = electricBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallStatus("THROTTLE", "— %", Modifier.weight(1f))
            SmallStatus("MODE", "Unknown", Modifier.weight(1f))
            SmallStatus("DIRECTION", "Unknown", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SmallStatus(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF12334D), RoundedCornerShape(8.dp)).padding(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label == "THROTTLE") {
                MotorcycleThrottleIcon()
                Spacer(Modifier.width(4.dp))
            }
            Text(label, color = Color(0xFF9DC4E1), fontSize = 9.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TemperatureCluster() {
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(Color(0xFF0D263D), RoundedCornerShape(12.dp))
        .border(1.dp, Color(0xFF185278), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("TEMPERATURE", color = electricBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TempMetric("▦", "Controller", Modifier.weight(1f))
            TempMetric("⚙", "Motor", Modifier.weight(1f))
        }
    }
}

@Composable
private fun TempMetric(icon: String, label: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF12334D), RoundedCornerShape(8.dp)).padding(7.dp)) {
        Text("$icon  $label", color = Color(0xFFAACDE5), fontSize = 10.sp)
        Spacer(Modifier.height(5.dp))
        Text("— °F (— °C)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FaultCluster() {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().background(Color(0xFF2A241B), RoundedCornerShape(12.dp))
        .border(1.dp, amber, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⚠", color = amber, fontSize = 26.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Fault status unavailable", color = amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Fault decoder not yet validated", color = Color.White, fontSize = 10.sp)
        }
        Text("›", color = amber, fontSize = 24.sp)
    }
}


/** Stylized twist-grip throttle and handlebar, not an accelerator pedal. */
@Composable
private fun MotorcycleThrottleIcon() {
    Canvas(Modifier.size(width = 29.dp, height = 19.dp)) {
        val scale = size.width / 29f
        // Handlebar shaft and throttle housing.
        drawLine(Color(0xFFB9D4E9), Offset(2f * scale, 10f * scale), Offset(26f * scale, 10f * scale), 3f * scale, cap = StrokeCap.Round)
        drawRoundRect(Color(0xFF38A9FA), topLeft = Offset(10f * scale, 5f * scale), size = Size(15f * scale, 10f * scale), cornerRadius = CornerRadius(4f * scale))
        // Grip ribs make this visually distinct from a pedal.
        for (x in listOf(14f, 18f, 22f)) {
            drawLine(Color(0xFF07314E), Offset(x * scale, 6f * scale), Offset((x - 2f) * scale, 14f * scale), 1.2f * scale)
        }
        drawLine(Color(0xFF83D9FF), Offset(7f * scale, 3f * scale), Offset(10f * scale, 5f * scale), 1.5f * scale, cap = StrokeCap.Round)
    }
}
