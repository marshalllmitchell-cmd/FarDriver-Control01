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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
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
                            "Tuning" -> TuningScreenV05()
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
            if (page == "Ride") FaultCluster()
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
                GaugePair(Modifier.fillMaxWidth(), 188.dp)
                ElectricalCluster()
            }
            Column(Modifier.weight(1f)) {
                DriveCluster()
                TemperatureCluster()
            }
        }
    } else {
        GaugePair(Modifier.fillMaxWidth(), 166.dp)
        Spacer(Modifier.height(4.dp))
        ElectricalCluster()
        DriveCluster()
        TemperatureCluster()
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
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(190, 216, 235)
                    textSize = 9.sp.toPx()
                    textAlign = Paint.Align.CENTER
                }
                for (i in 0..6) {
                    val angle = Math.toRadians((140.0 + i * 260.0 / 6.0))
                    val radius = diameter * .31f
                    val number = if (tach) (i * 2).toString() else (i * 20).toString()
                    drawContext.canvas.nativeCanvas.drawText(number,
                        cx + (radius * cos(angle)).toFloat(),
                        cy + (radius * sin(angle)).toFloat() + 3.sp.toPx(), paint)
                }
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
            MetricIcon(label)

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
        Text(value, color = if (label == "MODE" || label == "DIRECTION") amber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (label == "THROTTLE") {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF365269), RoundedCornerShape(3.dp)))
        }
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
    Spacer(Modifier.height(3.dp))
    Row(Modifier.fillMaxWidth().background(Color(0xFF2A241B), RoundedCornerShape(12.dp))
        .border(1.dp, amber, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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


/** Small consistent vector icons drawn locally, no third-party icon assets. */
@Composable
private fun MetricIcon(label: String) {
    Canvas(Modifier.size(18.dp)) {
        val c = electricBlue
        val w = size.width; val h = size.height
        when (label) {
            "Battery" -> {
                drawRoundRect(c, Offset(w*.08f,h*.25f), Size(w*.76f,h*.55f), CornerRadius(2.dp.toPx()), style = Stroke(1.8.dp.toPx()))
                drawRect(c, Offset(w*.85f,h*.42f), Size(w*.1f,h*.19f))
                drawLine(c, Offset(w*.2f,h*.53f), Offset(w*.48f,h*.53f), 1.4.dp.toPx())
            }
            "Battery Current" -> {
                drawRoundRect(c, Offset(w*.07f,h*.25f), Size(w*.75f,h*.55f), CornerRadius(2.dp.toPx()), style = Stroke(1.6.dp.toPx()))
                drawLine(Color(0xFFFFCB6D), Offset(w*.51f,h*.27f), Offset(w*.37f,h*.54f), 2.dp.toPx())
                drawLine(Color(0xFFFFCB6D), Offset(w*.37f,h*.54f), Offset(w*.58f,h*.54f), 2.dp.toPx())
                drawLine(Color(0xFFFFCB6D), Offset(w*.58f,h*.54f), Offset(w*.44f,h*.78f), 2.dp.toPx())
            }
            "Power" -> {
                for (i in 0..2) drawRoundRect(c, Offset(w*(.13f + i*.27f), h*(.65f-i*.2f)), Size(w*.16f,h*(.25f+i*.2f)), CornerRadius(1.dp.toPx()))
            }
            else -> {
                val points = listOf(0.05f to .55f,.25f to .55f,.35f to .25f,.5f to .8f,.65f to .4f,.8f to .55f,.95f to .55f)
                points.zipWithNext().forEach { (a,b) -> drawLine(c, Offset(w*a.first,h*a.second), Offset(w*b.first,h*b.second), 1.8.dp.toPx(), cap = StrokeCap.Round) }
            }
        }
    }
}


// Tuning v0.5: visual-only offline demonstration. Numeric values and ranges are
// NOT controller limits, and no BLE parameter reads or writes are implemented.
private data class DemoParameter(val key: String, val title: String, val unit: String, val min: Int, val max: Int, val initial: Int, val step: Int = 1)
private data class DemoGroup(val title: String, val parameters: List<DemoParameter>)
private val demoGroups = listOf(
    DemoGroup("Battery & Current", listOf(
        DemoParameter("battery", "Battery current limit", "A", 0, 300, 120, 5),
        DemoParameter("phase", "Phase current limit", "A", 0, 800, 350, 5),
        DemoParameter("voltage", "Rated battery voltage", "V", 0, 120, 72, 1))),
    DemoGroup("Speed Modes", listOf(
        DemoParameter("speed1", "Speed mode 1", "%", 0, 100, 30),
        DemoParameter("speed2", "Speed mode 2", "%", 0, 100, 60),
        DemoParameter("speed3", "Speed mode 3", "%", 0, 100, 100))),
    DemoGroup("Throttle Configuration", listOf(
        DemoParameter("throttle", "Throttle response", "%", 0, 100, 50))),
    DemoGroup("Regenerative Braking", listOf(
        DemoParameter("regen", "Regeneration strength", "%", 0, 100, 30))),
    DemoGroup("Motor Configuration", listOf(
        DemoParameter("poles", "Motor pole pairs", "", 1, 50, 23))),
    DemoGroup("Protection Settings", listOf(
        DemoParameter("lowvolt", "Low voltage cutoff", "V", 0, 120, 56))),
    DemoGroup("Advanced Parameters", listOf(
        DemoParameter("weakening", "Field weakening", "%", 0, 100, 0)))
)

@Composable
private fun TuningScreenV05() {
    val all = remember { demoGroups.flatMap { it.parameters } }
    val staged = remember { mutableStateMapOf<String, Int>().apply { all.forEach { put(it.key, it.initial) } } }
    val baseline = remember { all.associate { it.key to it.initial } }
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { demoGroups.forEachIndexed { index, g -> put(g.title, index == 0) } } }
    var savedProfile by remember { mutableStateOf<Map<String, Int>?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val changed = all.count { staged[it.key] != baseline[it.key] }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("PARAMETER EDITOR", color = electricBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("$changed STAGED", color = if (changed > 0) amber else Color(0xFFB4C7D7), fontSize = 11.sp)
    }
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().background(Color(0xFF31281B), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("OFFLINE DEMO • NOT CONTROLLER VALUES", color = amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("All example values and ranges are illustrative. They are not validated ND72530 limits. Nothing here can change the controller.", color = Color.White, fontSize = 11.sp)
    }
    Spacer(Modifier.height(10.dp))
    demoGroups.forEach { group ->
        val isOpen = expanded[group.title] == true
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color(0xFF0D263D), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF185278), RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().clickable { expanded[group.title] = !isOpen }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.title, color = electricBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${group.parameters.size}   ${if (isOpen) "▴" else "▾"}", color = Color.White, fontSize = 14.sp)
            }
            if (isOpen) {
                group.parameters.forEach { param ->
                    DemoParameterControl(param, staged[param.key] ?: param.initial, (staged[param.key] ?: param.initial) != baseline[param.key]) { staged[param.key] = it }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF0D263D), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("SAFETY REVIEW", color = amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("My Setup component ratings and controller parameter mapping are not validated. Safety recommendations and hardware writes remain unavailable.", color = Color.White, fontSize = 12.sp)
        TextButton(onClick = { showInfo = true }) { Text("Why are writes disabled?") }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("READ") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("WRITE") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { savedProfile = staged.toMap() }, modifier = Modifier.weight(1f)) { Text("SAVE DEMO") }
        OutlinedButton(onClick = { savedProfile?.let { staged.putAll(it) } }, enabled = savedProfile != null, modifier = Modifier.weight(1f)) { Text("LOAD DEMO") }
    }
    Text("Save/Load is in-memory only and resets when the app closes.", color = Color(0xFFB9CBDB), fontSize = 11.sp)
    TextButton(onClick = { baseline.forEach { (k, v) -> staged[k] = v } }) { Text("Discard staged demo changes") }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("Controller protection") },
        text = { Text("Parameter addresses, units, safe limits, controller read-back, and My Setup component ratings must be verified before any hardware write operation is enabled.") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } })
}

@Composable
private fun DemoParameterControl(param: DemoParameter, value: Int, modified: Boolean, onChange: (Int) -> Unit) {
    var editing by remember(param.key) { mutableStateOf(false) }
    var draft by remember(param.key, editing) { mutableStateOf(value.toString()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
        .background(Color(0xFF15344C), RoundedCornerShape(9.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(param.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (modified) Text("●", color = amber, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            if (editing) {
                OutlinedTextField(value = draft, onValueChange = { text -> if (text.length <= 7 && text.all { it.isDigit() }) draft = text },
                    modifier = Modifier.width(82.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp))
                TextButton(onClick = { draft.toIntOrNull()?.let { onChange(it.coerceIn(param.min, param.max)) }; editing = false }) { Text("✓") }
            } else {
                TextButton(onClick = { editing = true }) {
                    Text("$value ${param.unit} ✎", color = Color(0xFF8AD7FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((value - param.step).coerceAtLeast(param.min)) },
                modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
            Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(param.min, param.max)) },
                valueRange = param.min.toFloat()..param.max.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
            OutlinedButton(onClick = { onChange((value + param.step).coerceAtMost(param.max)) },
                modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${param.min} ${param.unit}", color = Color(0xFF9CBBD3), fontSize = 10.sp)
            Text("Example range only", color = amber, fontSize = 10.sp)
            Text("${param.max} ${param.unit}", color = Color(0xFF9CBBD3), fontSize = 10.sp)
        }
    }
}
