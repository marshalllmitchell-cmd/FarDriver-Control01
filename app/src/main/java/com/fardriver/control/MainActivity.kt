package com.fardriver.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val navy = Color(0xFF071725)
private val card = Color(0xFF102B43)
private val blue = Color(0xFF31AFFF)
private val amber = Color(0xFFFFC06C)
private val pale = Color(0xFFC0D7EA)

private data class Setup(val vehicle: String = "", val controller: String = "ND72530", val battery: String = "", val bms: String = "", val motor: String = "", val batteryLimit: String = "", val bmsLimit: String = "", val motorLimit: String = "", val notes: String = "")
private fun readSetup(context: Context): Setup {
    val p = context.getSharedPreferences("setup_v1", Context.MODE_PRIVATE)
    return Setup(p.getString("vehicle", "") ?: "", p.getString("controller", "ND72530") ?: "ND72530", p.getString("battery", "") ?: "", p.getString("bms", "") ?: "", p.getString("motor", "") ?: "", p.getString("batteryLimit", "") ?: "", p.getString("bmsLimit", "") ?: "", p.getString("motorLimit", "") ?: "", p.getString("notes", "") ?: "")
}
private fun saveSetup(context: Context, s: Setup) {
    context.getSharedPreferences("setup_v1", Context.MODE_PRIVATE).edit()
        .putString("vehicle", s.vehicle).putString("controller", s.controller)
        .putString("battery", s.battery).putString("bms", s.bms).putString("motor", s.motor)
        .putString("batteryLimit", s.batteryLimit).putString("bmsLimit", s.bmsLimit)
        .putString("motorLimit", s.motorLimit).putString("notes", s.notes).apply()
}
private data class Param(val key: String, val label: String, val unit: String, val min: Int, val max: Int, val initial: Int, val step: Int = 1)
private data class Group(val title: String, val params: List<Param>)
// All values below are mock UI data, NOT verified controller addresses, values or limits.
private val groups = listOf(
    Group("Battery & Current", listOf(Param("battery", "Battery current limit", "A", 0, 300, 120, 5), Param("phase", "Phase current limit", "A", 0, 800, 350, 5), Param("voltage", "Rated voltage", "V", 0, 120, 72))),
    Group("Speed Modes", listOf(Param("s1", "Speed mode 1", "%", 0, 100, 30), Param("s2", "Speed mode 2", "%", 0, 100, 60), Param("s3", "Speed mode 3", "%", 0, 100, 100))),
    Group("Throttle Configuration", listOf(Param("throttle", "Throttle response", "%", 0, 100, 50))),
    Group("Regenerative Braking", listOf(Param("regen", "Regeneration strength", "%", 0, 100, 30))),
    Group("Motor Configuration", listOf(Param("poles", "Motor pole pairs", "", 1, 50, 23))),
    Group("Protection Settings", listOf(Param("lvc", "Low voltage cutoff", "V", 0, 120, 56))),
    Group("Advanced Parameters", listOf(Param("field", "Field weakening", "%", 0, 100, 0)))
)
private val parameters = groups.flatMap { it.params }
private data class Concern(val title: String, val severity: String, val reason: String, val action: String, val evidence: String, val related: String)
private fun concerns(s: Setup, staged: Map<String, Int>): List<Concern> {
    val output = mutableListOf<Concern>()
    val battery = s.batteryLimit.toDoubleOrNull()?.takeIf { it > 0 }
    val bms = s.bmsLimit.toDoubleOrNull()?.takeIf { it > 0 }
    val limit = listOfNotNull(battery, bms).minOrNull()
    if (limit == null) output += Concern("Battery current rating needs verification", "Verification required",
        "The app cannot compare battery current against your actual hardware without verified continuous battery and BMS discharge ratings.",
        "Enter manufacturer-verified continuous discharge ratings in My Setup. Also verify wiring, connectors and thermal limits before applying a tune.",
        "Battery: ${s.battery.ifBlank { "not entered" }}; BMS: ${s.bms.ifBlank { "not entered" }}; continuous ratings: missing.", "battery")
    else {
        val demo = staged["battery"] ?: 120
        output += Concern("Battery current: setup comparison", "Illustrative only",
            "Your saved continuous current ratings have a lowest entered value of ${limit.toInt()} A. The editor currently shows a demonstration value of $demo A. This is NOT a real controller readout.",
            "Check that your ratings are manufacturer verified. Once real controller settings are read and validated, compare the actual current limit against the battery, BMS, wiring and connector ratings. Do not apply a numerical correction based on the demo slider.",
            "Saved battery limit: ${battery?.toInt()?.toString() ?: "unknown"} A; BMS limit: ${bms?.toInt()?.toString() ?: "unknown"} A; mock editor: $demo A.", "battery")
    }
    if (s.motorLimit.toDoubleOrNull()?.takeIf { it > 0 } == null) output += Concern("Motor thermal limit not verified", "Verification required",
        "No verified manufacturer motor temperature limit is stored. Live temperature decoding is also unavailable.",
        "Enter the verified motor temperature limit in My Setup and confirm sensor location and measurement method. Do not assume the motor is safe based on a blank reading.",
        "Motor: ${s.motor.ifBlank { "not entered" }}; thermal limit: missing; live telemetry: unavailable.", "motor")
    output += Concern("Regenerative braking compatibility", "Review needed",
        "Safe regenerative charging depends on battery chemistry, state of charge, BMS charge limits and controller configuration, which are not yet fully modeled.",
        "Confirm your battery and BMS charging limits and full-charge behavior. Do not increase regeneration based on the demonstration slider.",
        "Battery: ${s.battery.ifBlank { "not entered" }}; BMS: ${s.bms.ifBlank { "not entered" }}; verified regen charging limit: not stored.", "regen")
    return output
}

class MainActivity : ComponentActivity() {
    private var ble: BleManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("Disconnected") }
            var count by remember { mutableIntStateOf(0) }
            var frame by remember { mutableStateOf("No frames received") }
            var setup by remember { mutableStateOf(readSetup(this@MainActivity)) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants.values.all { it }) try { ble?.scan() } catch (e: Exception) { status = "Scan error: ${e.message}" }
                else status = "Bluetooth permissions denied"
            }
            val scan: () -> Unit = {
                try {
                    if (ble == null) ble = BleManager(this@MainActivity,
                        { message -> runOnUiThread { status = message } },
                        { data -> runOnUiThread { count++; frame = data.joinToString(" ") { "%02X".format(it.toInt() and 255) } } })
                    val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) ble?.scan() else launcher.launch(missing.toTypedArray())
                } catch (e: Exception) { status = "Bluetooth unavailable: ${e.message}" }
            }
            FarDriverUi(status, count, frame, setup, { setup = it; saveSetup(this@MainActivity, it) }, scan)
        }
    }
    override fun onDestroy() { try { ble?.close() } catch (_: Exception) { }; super.onDestroy() }
}

@Composable
private fun FarDriverUi(status: String, count: Int, frame: String, setup: Setup, onSaveSetup: (Setup) -> Unit, scan: () -> Unit) {
    var page by remember { mutableStateOf("Ride") }
    var menu by remember { mutableStateOf(false) }
    val pages = listOf("Ride", "Tuning", "Diagnostics", "My Setup")
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    val staged = remember { mutableStateMapOf<String, Int>().apply { parameters.forEach { put(it.key, it.initial) } } }
    var selectedConcern by remember { mutableStateOf<Concern?>(null) }
    var selectedSetting by remember { mutableStateOf<String?>(null) }
    val allConcerns = concerns(setup, staged)
    MaterialTheme(colorScheme = darkColorScheme(primary = blue, background = navy, surface = card, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(navy).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (wide) TextButton(onClick = { menu = !menu }) { Text("☰", fontSize = 23.sp) }
                Text("FARDRIVER", color = blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" CONTROL", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(if (status.startsWith("Connected", true)) "● Connected" else "● Disconnected", color = if (status.startsWith("Connected", true)) Color(0xFF4CE2AA) else amber, fontSize = 11.sp)
            }
            Row(Modifier.weight(1f)) {
                if (wide && menu) Column(Modifier.width(150.dp)) {
                    pages.forEach { label -> TextButton(onClick = { page = label; menu = false }) { Text(label) } }
                }
                LazyColumn(Modifier.weight(1f)) { item {
                    when (page) {
                        "Ride" -> RideScreen()
                        "Tuning" -> TuningScreen(staged, setup, allConcerns, selectedSetting, { selectedSetting = null }, { selectedConcern = it }, { page = "My Setup" })
                        "Diagnostics" -> DiagnosticsScreen(status, count, frame, scan, allConcerns, { selectedConcern = it })
                        else -> SetupScreen(setup, onSaveSetup)
                    }
                } }
            }
            if (page == "Ride") Row(Modifier.fillMaxWidth().background(Color(0xFF30261B), RoundedCornerShape(12.dp)).border(1.dp, amber, RoundedCornerShape(12.dp)).padding(9.dp)) {
                Text("⚠  Fault status unavailable: decoder not validated", color = amber, fontSize = 12.sp)
            }
            if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pages.forEach { label -> TextButton(onClick = { page = label }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when(label) { "Ride" -> "⌂"; "Tuning" -> "☷"; "Diagnostics" -> "▤"; else -> "⚙" }, color = if (page == label) blue else pale, fontSize = 22.sp)
                        Text(label, color = if (page == label) blue else pale, fontSize = 11.sp)
                    }
                } }
            }
        }
        selectedConcern?.let { c -> AlertDialog(onDismissRequest = { selectedConcern = null },
            title = { Text(c.title) },
            text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text("${c.severity} • ${setup.controller}", color = amber, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text("WHY THIS MATTERS", color = blue, fontWeight = FontWeight.Bold); Text(c.reason)
                Spacer(Modifier.height(8.dp)); Text("MY SETUP EVIDENCE", color = blue, fontWeight = FontWeight.Bold); Text(c.evidence)
                Spacer(Modifier.height(8.dp)); Text("WHAT YOU CAN DO", color = blue, fontWeight = FontWeight.Bold); Text(c.action)
                Spacer(Modifier.height(8.dp)); Text("No automatic parameter changes. This is not a certified safety assessment.", fontSize = 11.sp)
            } }, confirmButton = { TextButton(onClick = { selectedConcern = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { selectedSetting = c.related; selectedConcern = null; page = "Tuning" }) { Text("View setting") } }) }
    }
}

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(card, RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF215575), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(title, color = blue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp)); CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

@Composable private fun RideScreen() {
    Text("RIDE  •  LIVE MONITOR", color = pale, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RideGauge("SPEED", "MPH", "— km/h", false, Modifier.weight(1f))
        RideGauge("MOTOR RPM", "RPM", "×1000", true, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp)); Text("ELECTRICAL", color = blue, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric("▣ Battery", "— V", Modifier.weight(1f)); Metric("ϟ Current", "— A", Modifier.weight(1f)); Metric("▥ Power", "— kW", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { Metric("∿ Phase A", "— A", Modifier.weight(1f)); Metric("∿ Phase C", "— A", Modifier.weight(1f)) }
    Panel("DRIVE STATUS") {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Metric("THROTTLE", "— %", Modifier.weight(1f)); Metric("MODE", "Unknown", Modifier.weight(1f)); Metric("DIRECTION", "Unknown", Modifier.weight(1f))
        }
    }
    Panel("TEMPERATURE") { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric("Controller", "— °F (— °C)", Modifier.weight(1f)); Metric("Motor", "— °F (— °C)", Modifier.weight(1f))
    } }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(top = 6.dp).background(Color(0xFF153851), RoundedCornerShape(9.dp)).padding(10.dp)) {
        Text(label, color = pale, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp)); Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable private fun RideGauge(label: String, unit: String, secondary: String, tach: Boolean, modifier: Modifier) {
    Column(modifier.height(190.dp).padding(top = 7.dp).background(card, RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF215575), RoundedCornerShape(16.dp)).padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val d = size.minDimension; val cx = size.width / 2f; val cy = size.height / 2f
                val origin = Offset(cx-d/2,cy-d/2)
                drawArc(Color(0xFF365773), 140f, 260f, false, origin, Size(d,d), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                drawArc(if(tach) Color(0xFF91B9D4) else blue, 140f, if(tach) 200f else 260f, false, origin, Size(d,d), style = Stroke(3.dp.toPx()))
                if (tach) drawArc(Color(0xFFEF6262), 340f, 60f, false, origin, Size(d,d), style = Stroke(5.dp.toPx()))
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(190,215,235); textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER }
                for (i in 0..12) {
                    val a = Math.toRadians(140.0 + i * 260.0 / 12.0)
                    drawLine(pale, Offset(cx+(d*.39f*cos(a)).toFloat(),cy+(d*.39f*sin(a)).toFloat()), Offset(cx+(d*.43f*cos(a)).toFloat(),cy+(d*.43f*sin(a)).toFloat()),1.dp.toPx())
                    if(i%2==0) drawContext.canvas.nativeCanvas.drawText((if(tach) i else i*10).toString(),cx+(d*.30f*cos(a)).toFloat(),cy+(d*.30f*sin(a)).toFloat()+3.sp.toPx(),paint)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("—", color = Color.White, fontSize = 43.sp, fontWeight = FontWeight.Bold); Text(unit, color = Color.White, fontSize = 15.sp); Text(secondary, color = pale, fontSize = 10.sp) }
        }
    }
}

@Composable private fun TuningScreen(staged: androidx.compose.runtime.snapshots.SnapshotStateMap<String,Int>, setup: Setup, issues: List<Concern>, focus: String?, clearFocus: () -> Unit, onConcern: (Concern) -> Unit, openSetup: () -> Unit) {
    val expanded = remember { mutableStateMapOf<String,Boolean>().apply { groups.forEachIndexed { i,g -> put(g.title,i==0) } } }
    val original = remember { parameters.associate { it.key to it.initial } }
    var demoSaved by remember { mutableStateOf<Map<String,Int>?>(null) }
    Text("TUNING  •  OFFLINE STAGING", color = blue, fontWeight = FontWeight.Bold)
    Text("Example ranges only. READ/WRITE disabled. No controller values have been read.", color = amber, fontSize = 11.sp)
    Text("${parameters.count { staged[it.key] != original[it.key] }} modified demo values", color = pale, fontSize = 12.sp)
    groups.forEach { group ->
        val open = expanded[group.title] == true || group.params.any { it.key == focus }
        Panel(group.title) {
            Text(if(open) "▴ Collapse" else "▾ Expand", color = pale, modifier = Modifier.fillMaxWidth().clickable { expanded[group.title] = !open; clearFocus() }, fontSize = 12.sp)
            if(open) group.params.forEach { p -> ParameterRow(p, staged[p.key] ?: p.initial, (staged[p.key] ?: p.initial) != original[p.key], { staged[p.key] = it }) }
        }
    }
    Panel("SAFETY REVIEW • MY SETUP") {
        Text("Recommendations are informational. No verified controller parameter values or limits are available.", fontSize = 12.sp)
        issues.forEach { c -> Text("⚠ ${c.title}  ›", color = amber, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { onConcern(c) }.padding(vertical = 7.dp)) }
        TextButton(onClick = openSetup) { Text("Edit My Setup") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("READ") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("WRITE") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { demoSaved = staged.toMap() }, modifier = Modifier.weight(1f)) { Text("SAVE DEMO") }
        OutlinedButton(onClick = { demoSaved?.let { staged.putAll(it) } }, enabled = demoSaved != null, modifier = Modifier.weight(1f)) { Text("LOAD DEMO") }
    }
    Text("Demo profiles are temporary; no controller changes are made.", color = pale, fontSize = 11.sp)
    TextButton(onClick = { staged.putAll(original) }) { Text("Discard staged changes") }
}
@Composable private fun ParameterRow(p: Param, value: Int, changed: Boolean, onChange: (Int) -> Unit) {
    var editing by remember(p.key) { mutableStateOf(false) }
    var draft by remember(p.key,editing) { mutableStateOf(value.toString()) }
    Column(Modifier.fillMaxWidth().padding(top = 7.dp).background(Color(0xFF193B53), RoundedCornerShape(9.dp)).padding(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (changed) Text("●", color = amber)
            if (editing) {
                OutlinedTextField(draft, onValueChange = { if(it.length<=6 && it.all(Char::isDigit)) draft=it }, modifier = Modifier.width(82.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(onClick = { draft.toIntOrNull()?.let { onChange(it.coerceIn(p.min,p.max)) }; editing=false }) { Text("✓") }
            } else TextButton(onClick = { editing=true }) { Text("$value ${p.unit} ✎", color = blue, fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((value-p.step).coerceAtLeast(p.min)) }, modifier = Modifier.width(43.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
            Slider(value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(p.min,p.max)) }, valueRange = p.min.toFloat()..p.max.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            OutlinedButton(onClick = { onChange((value+p.step).coerceAtMost(p.max)) }, modifier = Modifier.width(43.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
        Text("Illustrative scale: ${p.min}–${p.max} ${p.unit}", color = amber, fontSize = 10.sp)
    }
}

@Composable private fun DiagnosticsScreen(status: String, count: Int, frame: String, scan: () -> Unit, issues: List<Concern>, onConcern: (Concern) -> Unit) {
    Text("DIAGNOSTICS & ADVANCED MONITORING", color = blue, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Panel("BLUETOOTH") { Button(onClick = scan) { Text("Scan & Connect") }; Text("Status: $status\nNotifications: $count\nLast raw frame: $frame", fontSize = 11.sp) }
    Panel("ERROR / FAULT HISTORY") { Text("No validated fault history. This does NOT mean no faults exist.", fontSize = 12.sp) }
    Panel("POTENTIAL SAFETY CONCERNS") {
        issues.forEach { c -> Row(Modifier.fillMaxWidth().clickable { onConcern(c) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = amber); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(c.severity, color = amber, fontSize = 11.sp) }; Text("›", color = blue, fontSize = 22.sp)
        } }
        Text("Tap a concern for a My Setup-specific explanation and suggested checks.", color = pale, fontSize = 11.sp)
    }
    Panel("LIVE DATA LOGGER") { Text("Session recording, fault timestamps and CSV export are not implemented yet.", fontSize = 12.sp) }
    Panel("BEFORE / AFTER TUNE COMPARISON") { Text("Requires saved rides and validated telemetry. No results available.", fontSize = 12.sp) }
}

@Composable private fun SetupScreen(saved: Setup, onSave: (Setup) -> Unit) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var message by remember { mutableStateOf("") }
    Text("MY SETUP", color = blue, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text("Saved locally on this phone. Enter only verified manufacturer ratings.", color = pale, fontSize = 12.sp)
    Panel("VEHICLE & CONTROLLER") {
        SetupField("Vehicle name",draft.vehicle) { draft=draft.copy(vehicle=it) }
        SetupField("Controller model",draft.controller) { draft=draft.copy(controller=it) }
    }
    Panel("BATTERY & BMS") {
        SetupField("Battery make/model",draft.battery) { draft=draft.copy(battery=it) }
        SetupField("Battery continuous discharge rating (A)",draft.batteryLimit,true) { draft=draft.copy(batteryLimit=it) }
        SetupField("BMS make/model",draft.bms) { draft=draft.copy(bms=it) }
        SetupField("BMS continuous discharge rating (A)",draft.bmsLimit,true) { draft=draft.copy(bmsLimit=it) }
    }
    Panel("MOTOR") {
        SetupField("Motor make/model",draft.motor) { draft=draft.copy(motor=it) }
        SetupField("Verified motor temperature limit (°C)",draft.motorLimit,true) { draft=draft.copy(motorLimit=it) }
    }
    Panel("OTHER COMPONENTS / NOTES") { SetupField("Drivetrain, throttle, cooling, braking, manufacturer references",draft.notes) { draft=draft.copy(notes=it) } }
    Button(onClick = { onSave(draft); message="My Setup saved locally" }, modifier = Modifier.fillMaxWidth()) { Text("SAVE MY SETUP") }
    Text(message, color = Color(0xFF5CE0A8), fontSize = 12.sp)
    Text("Saved ratings are user-entered, not independently verified by this app. Safety rules require real controller data before recommending numeric changes.", color = amber, fontSize = 11.sp)
}
@Composable private fun SetupField(label: String, value: String, numeric: Boolean = false, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { text -> if(!numeric || (text.length<=10 && text.all { it.isDigit() || it=='.' })) onValue(text) },
        label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if(numeric) KeyboardType.Decimal else KeyboardType.Text))
}
