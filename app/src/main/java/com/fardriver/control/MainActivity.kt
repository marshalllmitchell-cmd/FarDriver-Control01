package com.fardriver.control

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val navy = Color(0xFF071725)
private val panel = Color(0xFF102B43)
private val blue = Color(0xFF31AFFF)
private val amber = Color(0xFFFFC06C)
private val pale = Color(0xFFC0D7EA)
private val green = Color(0xFF4CE2AA)

private enum class LinkState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECEIVING, RECONNECTING }
private data class Health(
    val state: LinkState = LinkState.DISCONNECTED,
    val name: String = "Not selected", val rssi: Int? = null,
    val connectedAt: Long = 0L, val lastPacketAt: Long = 0L,
    val notifications: Int = 0, val recentPackets: List<Long> = emptyList(),
    val lastHex: String = "No notifications", val event: String = "Ready",
    val rawLogging: Boolean = false, val logCount: Int = 0
)
private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

/** One-file v0.7 BLE client. Does not conflict with an existing BleManager.kt. Read-only. */
@SuppressLint("MissingPermission")
private class DiagnosticBleClient(
    private val activity: Activity,
    private val report: (Health) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(BluetoothManager::class.java)
    private val serviceId = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicId = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val found = linkedMapOf<String, Triple<BluetoothDevice, String, Int>>()
    private var h = Health()
    private var gatt: BluetoothGatt? = null
    private var selected: BluetoothDevice? = null
    private var scanning = false
    private var closed = false
    private var manualStop = false
    private var attempts = 0
    private var scanTask: Runnable? = null
    private var retryTask: Runnable? = null
    private var timeoutTask: Runnable? = null
    private var logFile: File? = null
    private val logBuffer = ArrayList<String>()
    private var lastFlush = 0L

    private fun publish(value: Health) { h = value; report(value) }
    private fun message(text: String) { publish(h.copy(event = text)) }
    private fun state(s: LinkState, text: String) { publish(h.copy(state = s, event = text)) }
    private fun permitted(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val scannerCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || closed) return
            val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: Exception) { null } ?: "Unnamed device"
            found[result.device.address] = Triple(result.device, name, result.rssi)
            if (found.size == 1 || found.size % 5 == 0) message("Found ${found.size} nearby BLE devices")
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            state(LinkState.DISCONNECTED, "Scan failed: $errorCode")
        }
    }
    fun scan() {
        if (closed || !permitted()) { message("Bluetooth permissions required"); return }
        if (scanning) return
        if (h.state == LinkState.CONNECTED || h.state == LinkState.RECEIVING) { message("Already connected"); return }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { message("Enable Bluetooth"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { message("BLE scanner unavailable"); return }
        manualStop = false
        found.clear()
        try {
            scanning = true
            scanner.startScan(scannerCallback)
            state(LinkState.SCANNING, "Scanning all nearby BLE devices")
            val task = Runnable { if (scanning) { stopScan(); showPicker() } }
            scanTask = task
            handler.postDelayed(task, 10000L)
        } catch (e: Exception) { scanning = false; state(LinkState.DISCONNECTED, "Scan error: ${e.message}") }
    }
    private fun stopScan() {
        scanTask?.let { handler.removeCallbacks(it) }; scanTask = null
        if (!scanning) return
        scanning = false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: Exception) { }
    }
    private fun showPicker() {
        if (closed || activity.isFinishing) return
        val choices = found.values.sortedByDescending { it.third }
        if (choices.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("BLE scan").setMessage("No advertising devices found. Check controller power and Bluetooth permissions.").setPositiveButton("OK", null).show()
            state(LinkState.DISCONNECTED, "No BLE devices found")
            return
        }
        val labels = choices.map { "${it.second} (${it.third} dBm)\n${it.first.address}" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Choose controller BLE module")
            .setItems(labels) { _, index ->
                selected = choices[index].first
                attempts = 0
                connect(choices[index].first, choices[index].second, choices[index].third)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun connect(device: BluetoothDevice, name: String, rssi: Int?) {
        if (closed) return
        cancelTimeout()
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        publish(h.copy(state = if (attempts > 0) LinkState.RECONNECTING else LinkState.CONNECTING,
            name = name, rssi = rssi, connectedAt = 0, lastPacketAt = 0, recentPackets = emptyList(), event = "Connecting"))
        try {
            gatt = device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE)
            val task = Runnable {
                if (h.state == LinkState.CONNECTING || h.state == LinkState.RECONNECTING) {
                    message("Connection timed out")
                    try { gatt?.close() } catch (_: Exception) { }
                    gatt = null
                    retry()
                }
            }
            timeoutTask = task
            handler.postDelayed(task, 15000L)
        } catch (e: Exception) { state(LinkState.DISCONNECTED, "Connection error: ${e.message}") }
    }
    private fun cancelTimeout() { timeoutTask?.let { handler.removeCallbacks(it) }; timeoutTask = null }
    private fun retry() {
        if (closed || manualStop || attempts >= 2 || selected == null) {
            state(LinkState.DISCONNECTED, "Disconnected; scan to retry")
            return
        }
        attempts++
        state(LinkState.RECONNECTING, "Reconnect attempt $attempts of 2")
        val task = Runnable { selected?.let { connect(it, h.name, h.rssi) } }
        retryTask = task
        handler.postDelayed(task, attempts * 2000L)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (closed || (gatt != null && gatt !== g)) { g.close(); return@post }
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    message("GATT error $status")
                    try { g.close() } catch (_: Exception) { }
                    if (gatt === g) gatt = null
                    retry()
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    attempts = 0
                    publish(h.copy(state = LinkState.CONNECTED, connectedAt = SystemClock.elapsedRealtime(), event = "GATT connected"))
                    try { g.discoverServices() } catch (e: Exception) { message("Service discovery error: ${e.message}") }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try { g.close() } catch (_: Exception) { }
                    if (gatt === g) gatt = null
                    retry()
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) { message("Service discovery error $status"); return@post }
                val c = g.getService(serviceId)?.getCharacteristic(characteristicId)
                if (c == null) { message("FFE0/FFEC not found (${g.services.size} services)"); return@post }
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) { message("FFEC has no notify property"); return@post }
                if (!g.setCharacteristicNotification(c, true)) { message("Notification setup failed"); return@post }
                val descriptor = c.getDescriptor(cccdId) ?: run { message("CCCD missing"); return@post }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                message(if (started) "Notification subscription requested" else "Notification subscription failed")
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { message(if (status == BluetoothGatt.GATT_SUCCESS) "Notifications enabled" else "CCCD error $status") }
        }
        @Deprecated("Older Android callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value?.clone() ?: byteArrayOf()
            if (characteristic.uuid == characteristicId) receive(data)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == characteristicId) receive(value.clone())
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { publish(h.copy(rssi = rssi)) }
        }
    }
    private fun receive(data: ByteArray) {
        handler.post {
            if (closed || data.isEmpty()) return@post
            val now = SystemClock.elapsedRealtime()
            val recent = (h.recentPackets + now).filter { now - it <= 5000L }.takeLast(200)
            publish(h.copy(state = LinkState.RECEIVING, lastPacketAt = now, notifications = h.notifications + 1,
                recentPackets = recent, lastHex = hex(data), event = "Receiving BLE notifications"))
            if (h.rawLogging) {
                logBuffer.add("${stamp()},${data.size},${hex(data).replace(" ", "")}")
                if (logBuffer.size >= 25 || now - lastFlush > 2000L) flush()
                publish(h.copy(logCount = h.logCount + 1))
            }
        }
    }
    fun tick() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (h.state == LinkState.RECEIVING && now - h.lastPacketAt > 3500L) state(LinkState.CONNECTED, "Connected; waiting for notifications")
        if (h.state == LinkState.CONNECTED || h.state == LinkState.RECEIVING) try { gatt?.readRemoteRssi() } catch (_: Exception) { }
        if (h.rawLogging && logBuffer.isNotEmpty()) flush()
    }
    fun toggleLogging() {
        if (h.rawLogging) {
            flush(); publish(h.copy(rawLogging = false, event = "Raw log saved: ${logFile?.name ?: "none"}"))
        } else {
            try {
                val dir = File(activity.filesDir, "ble_logs").apply { mkdirs() }
                logFile = File(dir, "ble_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
                logFile!!.writeText("timestamp,bytes,hex\n")
                logBuffer.clear(); lastFlush = SystemClock.elapsedRealtime()
                publish(h.copy(rawLogging = true, logCount = 0, event = "Recording raw BLE frames"))
            } catch (e: Exception) { message("Unable to start logging: ${e.message}") }
        }
    }
    private fun flush() {
        if (logBuffer.isEmpty()) return
        try { logFile?.appendText(logBuffer.joinToString("\n", postfix = "\n")); logBuffer.clear(); lastFlush = SystemClock.elapsedRealtime() }
        catch (e: Exception) { publish(h.copy(rawLogging = false, event = "Log write error: ${e.message}")) }
    }
    fun logLocation() = logFile?.absolutePath ?: "No log created"
    fun close() {
        if (closed) return
        manualStop = true
        stopScan(); cancelTimeout()
        retryTask?.let { handler.removeCallbacks(it) }
        flush(); closed = true
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
    }
}

private data class Setup(val vehicle: String = "", val controller: String = "ND72530", val battery: String = "", val bms: String = "", val motor: String = "", val batteryLimit: String = "", val bmsLimit: String = "", val motorLimit: String = "")
private fun loadSetup(c: Context): Setup {
    val p = c.getSharedPreferences("setup_v1", Context.MODE_PRIVATE)
    fun v(key: String, fallback: String = "") = p.getString(key, fallback) ?: fallback
    return Setup(v("vehicle"), v("controller", "ND72530"), v("battery"), v("bms"), v("motor"), v("batteryLimit"), v("bmsLimit"), v("motorLimit"))
}
private fun storeSetup(c: Context, s: Setup) {
    c.getSharedPreferences("setup_v1", Context.MODE_PRIVATE).edit()
        .putString("vehicle", s.vehicle).putString("controller", s.controller)
        .putString("battery", s.battery).putString("bms", s.bms).putString("motor", s.motor)
        .putString("batteryLimit", s.batteryLimit).putString("bmsLimit", s.bmsLimit).putString("motorLimit", s.motorLimit).apply()
}
private data class Issue(val title: String, val reason: String, val action: String)
private fun safetyIssues(s: Setup): List<Issue> {
    val issues = mutableListOf<Issue>()
    val battery = s.batteryLimit.toDoubleOrNull()?.takeIf { it > 0 }
    val bms = s.bmsLimit.toDoubleOrNull()?.takeIf { it > 0 }
    issues += if (battery == null || bms == null) Issue("Battery/BMS limits need verification",
        "My Setup does not contain both verified continuous discharge ratings.",
        "Enter manufacturer-verified battery and BMS continuous discharge limits; check wiring and connectors too.")
    else Issue("Battery current compatibility review", "My Setup lists battery $battery A and BMS $bms A continuous. These are user-entered values; actual controller current is not decoded.",
        "Compare verified limits with actual controller settings only after validated parameter readback. Do not use demonstration sliders for safety decisions.")
    if (s.motorLimit.toDoubleOrNull()?.takeIf { it > 0 } == null) issues += Issue("Motor thermal limit unknown",
        "My Setup has no verified motor temperature limit, and live motor temperature is not decoded.",
        "Enter the manufacturer's motor thermal limit and verify sensor placement.")
    issues += Issue("Regenerative braking compatibility", "Battery and BMS charge limits are not fully modeled.",
        "Confirm the battery/BMS charge limits and full-charge behavior before changing regen settings.")
    return issues
}

class MainActivity : ComponentActivity() {
    private var client: DiagnosticBleClient? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var health by remember { mutableStateOf(Health()) }
            var setup by remember { mutableStateOf(loadSetup(this@MainActivity)) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions.values.all { it }) client?.scan()
                else health = health.copy(event = "Bluetooth permissions denied")
            }
            val scan: () -> Unit = {
                try {
                    if (client == null) client = DiagnosticBleClient(this@MainActivity) { health = it }
                    val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) client?.scan() else launcher.launch(missing.toTypedArray())
                } catch (e: Exception) { health = health.copy(event = "BLE error: ${e.message}") }
            }
            var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while (true) { delay(1000); client?.tick(); clock = SystemClock.elapsedRealtime() } }
            AppContent(health, clock, setup, { setup = it; storeSetup(this@MainActivity, it) }, scan,
                { client?.toggleLogging() }, { client?.logLocation() ?: "No log created" })
        }
    }
    override fun onDestroy() { client?.close(); super.onDestroy() }
}

@Composable
private fun AppContent(health: Health, clock: Long, setup: Setup, saveSetup: (Setup) -> Unit,
                       scan: () -> Unit, toggleLog: () -> Unit, logPath: () -> String) {
    var page by remember { mutableStateOf("Ride") }
    var menuOpen by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<Issue?>(null) }
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    val tabs = listOf("Ride", "Tuning", "Diagnostics", "My Setup")
    val statusText = when (health.state) {
        LinkState.RECEIVING -> "Receiving Data"
        LinkState.CONNECTED -> "Connected"
        LinkState.RECONNECTING -> "Reconnecting"
        LinkState.CONNECTING -> "Connecting"
        LinkState.SCANNING -> "Scanning"
        LinkState.DISCONNECTED -> "Disconnected"
    }
    val statusColor = when (health.state) {
        LinkState.RECEIVING -> blue
        LinkState.CONNECTED -> green
        LinkState.RECONNECTING, LinkState.CONNECTING, LinkState.SCANNING -> amber
        LinkState.DISCONNECTED -> Color(0xFFFF7777)
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = blue, background = navy, surface = panel, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(navy).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (wide) TextButton(onClick = { menuOpen = !menuOpen }) { Text("☰") }
                Text("FARDRIVER", color = blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" CONTROL", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text("● $statusText", color = statusColor, fontSize = 11.sp)
            }
            Row(Modifier.weight(1f)) {
                if (wide && menuOpen) Column(Modifier.width(150.dp)) {
                    tabs.forEach { tab -> TextButton(onClick = { page = tab; menuOpen = false }) { Text(tab) } }
                }
                LazyColumn(Modifier.weight(1f)) { item {
                    when (page) {
                        "Ride" -> RideScreen()
                        "Tuning" -> TuningScreen(setup, { issue = it }, { page = "My Setup" })
                        "Diagnostics" -> DiagnosticsScreen(health, clock, scan, toggleLog, logPath, setup, { issue = it })
                        else -> SetupScreen(setup, saveSetup)
                    }
                } }
            }
            if (page == "Ride") Row(Modifier.fillMaxWidth().background(Color(0xFF30261B), RoundedCornerShape(10.dp)).border(1.dp, amber, RoundedCornerShape(10.dp)).padding(8.dp)) {
                Text("⚠  Fault status unavailable: decoder not validated", color = amber, fontSize = 12.sp)
            }
            if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEach { tab -> TextButton(onClick = { page = tab }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when(tab) { "Ride" -> "⌂"; "Tuning" -> "☷"; "Diagnostics" -> "▤"; else -> "⚙" }, color = if (page == tab) blue else pale, fontSize = 21.sp)
                        Text(tab, color = if (page == tab) blue else pale, fontSize = 11.sp)
                    }
                } }
            }
        }
        issue?.let { selected -> AlertDialog(onDismissRequest = { issue = null },
            title = { Text(selected.title) },
            text = { Column {
                Text("MY SETUP: ${setup.controller}", color = amber, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text("WHY THIS MATTERS", color = blue); Text(selected.reason)
                Spacer(Modifier.height(8.dp)); Text("WHAT YOU CAN DO", color = blue); Text(selected.action)
                Spacer(Modifier.height(8.dp)); Text("Information only. No controller changes will be made.", fontSize = 11.sp)
            } }, confirmButton = { TextButton(onClick = { issue = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { issue = null; page = "My Setup" }) { Text("My Setup") } }) }
    }
}

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(panel, RoundedCornerShape(12.dp))
        .border(1.dp, Color(0xFF215575), RoundedCornerShape(12.dp)).padding(11.dp)) {
        Text(title, color = blue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(7.dp))
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

// Approved ride layout: gauge pair, electrical tiles, throttle grip, temperatures and pinned fault.
@Composable private fun RideScreen() {
    Text("RIDE  •  LIVE MONITOR", color = pale, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RideGauge("SPEED", "MPH", "— km/h", false, Modifier.weight(1f))
        RideGauge("MOTOR RPM", "RPM", "×1000", true, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp)); Text("ELECTRICAL", color = blue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RideMetric("Battery", "V", Modifier.weight(1f)); RideMetric("Battery Current", "A", Modifier.weight(1f)); RideMetric("Power", "kW", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RideMetric("Phase A", "A", Modifier.weight(1f)); RideMetric("Phase C", "A", Modifier.weight(1f))
    }
    Panel("DRIVE STATUS") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RideStatus("THROTTLE", "— %", Modifier.weight(1f)); RideStatus("MODE", "Unknown", Modifier.weight(1f)); RideStatus("DIRECTION", "Unknown", Modifier.weight(1f))
        }
    }
    Panel("TEMPERATURE") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RideTemperature("Controller", Modifier.weight(1f)); RideTemperature("Motor", Modifier.weight(1f))
        }
    }
}
@Composable private fun RideGauge(label: String, unit: String, secondary: String, tach: Boolean, modifier: Modifier) {
    Column(modifier.height(166.dp).background(Brush.verticalGradient(listOf(Color(0xFF17334C), Color(0xFF091B2C))), RoundedCornerShape(16.dp))
        .border(1.dp, Color(0xFF285A79), RoundedCornerShape(16.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val d = size.minDimension; val cx = size.width/2f; val cy = size.height/2f
                val origin = Offset(cx-d/2f,cy-d/2f)
                drawArc(Color(0xFF365773),140f,260f,false,origin,Size(d,d),style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
                drawArc(if(tach) Color(0xFF91B9D4) else blue,140f,if(tach) 200f else 260f,false,origin,Size(d,d),style=Stroke(3.dp.toPx()))
                if(tach) drawArc(Color(0xFFEF6262),340f,60f,false,origin,Size(d,d),style=Stroke(5.dp.toPx()))
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color=android.graphics.Color.rgb(190,215,235); textSize=9.sp.toPx(); textAlign=android.graphics.Paint.Align.CENTER
                }
                for(i in 0..12) {
                    val angle=Math.toRadians(140.0+i*260.0/12.0)
                    drawLine(pale,Offset(cx+(d*.39f*cos(angle)).toFloat(),cy+(d*.39f*sin(angle)).toFloat()),Offset(cx+(d*.43f*cos(angle)).toFloat(),cy+(d*.43f*sin(angle)).toFloat()),1.dp.toPx())
                    if(i%2==0) drawContext.canvas.nativeCanvas.drawText((if(tach) i else i*10).toString(),cx+(d*.30f*cos(angle)).toFloat(),cy+(d*.30f*sin(angle)).toFloat()+3.sp.toPx(),paint)
                }
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text("—",color=Color.White,fontSize=43.sp,fontWeight=FontWeight.Bold)
                Text(unit,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Bold)
                Text(secondary,color=pale,fontSize=10.sp)
            }
        }
    }
}
@Composable private fun RideMetric(label: String, unit: String, modifier: Modifier) {
    Column(modifier.padding(top=7.dp).background(panel,RoundedCornerShape(10.dp))
        .border(1.dp,Color(0xFF235C82),RoundedCornerShape(10.dp)).padding(9.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            RideIcon(label); Spacer(Modifier.width(5.dp))
            Text(label,color=pale,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(11.dp)); Text("— $unit",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable private fun RideIcon(label: String) {
    Canvas(Modifier.size(18.dp)) {
        val w=size.width; val h=size.height
        when(label) {
            "Battery", "Battery Current" -> {
                drawRoundRect(blue,Offset(w*.08f,h*.25f),Size(w*.76f,h*.55f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),style=Stroke(1.8.dp.toPx()))
                drawRect(blue,Offset(w*.85f,h*.42f),Size(w*.1f,h*.19f))
                if(label=="Battery Current") {
                    drawLine(amber,Offset(w*.52f,h*.26f),Offset(w*.37f,h*.55f),2.dp.toPx())
                    drawLine(amber,Offset(w*.37f,h*.55f),Offset(w*.58f,h*.55f),2.dp.toPx())
                    drawLine(amber,Offset(w*.58f,h*.55f),Offset(w*.44f,h*.78f),2.dp.toPx())
                }
            }
            "Power" -> for(i in 0..2) drawRoundRect(blue,Offset(w*(.13f+i*.27f),h*(.65f-i*.2f)),Size(w*.16f,h*(.25f+i*.2f)),androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
            else -> {
                val points=listOf(.05f to .55f,.25f to .55f,.35f to .25f,.5f to .8f,.65f to .4f,.8f to .55f,.95f to .55f)
                points.zipWithNext().forEach { (a,b) -> drawLine(blue,Offset(w*a.first,h*a.second),Offset(w*b.first,h*b.second),1.8.dp.toPx(),cap=StrokeCap.Round) }
            }
        }
    }
}
@Composable private fun RideStatus(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF163B55),RoundedCornerShape(9.dp)).padding(7.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            if(label=="THROTTLE") { MotorcycleThrottleIcon(); Spacer(Modifier.width(3.dp)) }
            Text(label,color=pale,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text(value,color=if(label=="THROTTLE") Color.White else amber,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=1)
        if(label=="THROTTLE") { Spacer(Modifier.height(6.dp)); Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF45657B),RoundedCornerShape(3.dp))) }
    }
}
@Composable private fun MotorcycleThrottleIcon() {
    Canvas(Modifier.size(width=29.dp,height=19.dp)) {
        val k=size.width/29f
        drawLine(pale,Offset(2f*k,10f*k),Offset(26f*k,10f*k),3f*k,cap=StrokeCap.Round)
        drawRoundRect(blue,Offset(10f*k,5f*k),Size(15f*k,10f*k),androidx.compose.ui.geometry.CornerRadius(4f*k))
        for(x in listOf(14f,18f,22f)) drawLine(Color(0xFF07314E),Offset(x*k,6f*k),Offset((x-2f)*k,14f*k),1.2f*k)
        drawLine(Color(0xFF83D9FF),Offset(7f*k,3f*k),Offset(10f*k,5f*k),1.5f*k)
    }
}
@Composable private fun RideTemperature(label: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF163B55),RoundedCornerShape(9.dp)).padding(8.dp)) {
        Text((if(label=="Motor") "⚙ " else "▦ ")+label,color=pale,fontSize=11.sp)
        Spacer(Modifier.height(9.dp)); Text("— °F (— °C)",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
    }
}

private data class DemoParam(val id: String, val title: String, val unit: String, val min: Int, val max: Int, val initial: Int)
private data class DemoGroup(val title: String, val items: List<DemoParam>)
private val demoGroups = listOf(
    DemoGroup("Battery & Current", listOf(DemoParam("battery", "Battery current limit", "A", 0, 300, 120), DemoParam("phase", "Phase current limit", "A", 0, 800, 350))),
    DemoGroup("Speed Modes", listOf(DemoParam("s1", "Speed mode 1", "%", 0, 100, 30), DemoParam("s2", "Speed mode 2", "%", 0, 100, 60), DemoParam("s3", "Speed mode 3", "%", 0, 100, 100))),
    DemoGroup("Throttle Configuration", listOf(DemoParam("throttle", "Throttle response", "%", 0, 100, 50))),
    DemoGroup("Regenerative Braking", listOf(DemoParam("regen", "Regen strength", "%", 0, 100, 30))),
    DemoGroup("Motor Configuration", listOf(DemoParam("poles", "Motor pole pairs", "", 1, 50, 23))),
    DemoGroup("Protection Settings", listOf(DemoParam("lvc", "Low voltage cutoff", "V", 0, 120, 56))),
    DemoGroup("Advanced Parameters", listOf(DemoParam("field", "Field weakening", "%", 0, 100, 0)))
)
@Composable private fun TuningScreen(setup: Setup, showIssue: (Issue) -> Unit, openSetup: () -> Unit) {
    val all = remember { demoGroups.flatMap { it.items } }
    val values = remember { mutableStateMapOf<String, Int>().apply { all.forEach { put(it.id, it.initial) } } }
    val opened = remember { mutableStateMapOf<String, Boolean>().apply { demoGroups.forEachIndexed { i, g -> put(g.title, i == 0) } } }
    var demoSave by remember { mutableStateOf<Map<String, Int>?>(null) }
    Text("TUNING  •  OFFLINE STAGING", color = blue, fontWeight = FontWeight.Bold)
    Text("Illustrative values only. Controller read/write is disabled.", color = amber, fontSize = 11.sp)
    Text("${all.count { values[it.id] != it.initial }} modified demo settings", color = pale, fontSize = 12.sp)
    demoGroups.forEach { group -> Panel(group.title) {
        Text(if (opened[group.title] == true) "▴ Collapse" else "▾ Expand", color = pale,
            modifier = Modifier.fillMaxWidth().clickable { opened[group.title] = opened[group.title] != true })
        if (opened[group.title] == true) group.items.forEach { p ->
            var edit by remember(p.id) { mutableStateOf(false) }
            var input by remember(p.id, edit) { mutableStateOf((values[p.id] ?: p.initial).toString()) }
            val value = values[p.id] ?: p.initial
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF193B53), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (value != p.initial) Text("●", color = amber)
                    if (edit) {
                        OutlinedTextField(input, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) input = it }, modifier = Modifier.width(82.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(onClick = { input.toIntOrNull()?.let { values[p.id] = it.coerceIn(p.min, p.max) }; edit = false }) { Text("✓") }
                    } else TextButton(onClick = { edit = true }) { Text("$value ${p.unit} ✎", color = blue, fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { values[p.id] = (value - 1).coerceAtLeast(p.min) }, modifier = Modifier.width(43.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
                    Slider(value.toFloat(), onValueChange = { values[p.id] = it.toInt() }, valueRange = p.min.toFloat()..p.max.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    OutlinedButton(onClick = { values[p.id] = (value + 1).coerceAtMost(p.max) }, modifier = Modifier.width(43.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                }
                Text("Example range: ${p.min}–${p.max} ${p.unit}", color = amber, fontSize = 10.sp)
            }
        }
    } }
    Panel("SAFETY REVIEW • MY SETUP") {
        safetyIssues(setup).forEach { item -> Text("⚠ ${item.title} ›", color = amber, modifier = Modifier.fillMaxWidth().clickable { showIssue(item) }.padding(vertical = 7.dp), fontSize = 12.sp) }
        TextButton(onClick = openSetup) { Text("Edit My Setup") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("READ") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("WRITE") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { demoSave = values.toMap() }, modifier = Modifier.weight(1f)) { Text("SAVE DEMO") }
        OutlinedButton(onClick = { demoSave?.let { values.putAll(it) } }, enabled = demoSave != null, modifier = Modifier.weight(1f)) { Text("LOAD DEMO") }
    }
    Text("Demo profiles reset on app restart. No hardware commands are sent.", color = pale, fontSize = 11.sp)
}

@Composable private fun DiagnosticsScreen(h: Health, clock: Long, scan: () -> Unit, toggleLog: () -> Unit, logPath: () -> String, setup: Setup, showIssue: (Issue) -> Unit) {
    val now = clock
    val age = if (h.lastPacketAt > 0) (now - h.lastPacketAt).coerceAtLeast(0L) / 1000.0 else null
    val duration = if (h.connectedAt > 0) (now - h.connectedAt).coerceAtLeast(0L) / 1000L else 0L
    val rate = h.recentPackets.count { now - it <= 5000L } / 5.0
    Text("DIAGNOSTICS & ADVANCED MONITORING", color = blue, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Panel("BLUETOOTH CONNECTION") {
        Button(onClick = scan) { Text("Scan & Connect") }
        Text("State: ${h.state.name.replace('_', ' ')}", color = if (h.state == LinkState.RECEIVING) green else amber)
        Text("Device: ${h.name}\nRSSI: ${h.rssi?.let { "$it dBm" } ?: "Unavailable"}\nNotifications: ${h.notifications}", fontSize = 12.sp)
        Text("Rate: ${"%.1f".format(Locale.US, rate)} notifications/sec (5-second average)", fontSize = 12.sp)
        Text("Last notification: ${age?.let { "%.1f sec ago".format(Locale.US, it) } ?: "None"}", fontSize = 12.sp)
        Text("Connected for: ${duration / 60}m ${duration % 60}s", fontSize = 12.sp)
        Text("Event: ${h.event}", color = pale, fontSize = 11.sp)
        Text("Last raw notification: ${h.lastHex}", fontSize = 11.sp)
    }
    Panel("RAW DATA LOGGER") {
        Text("Captures timestamped BLE notifications, not validated telemetry frames.", fontSize = 12.sp)
        Button(onClick = toggleLog) { Text(if (h.rawLogging) "Stop & Save Log" else "Start Raw Logging") }
        Text("Logged notifications: ${h.logCount}\nFile: ${logPath()}", fontSize = 11.sp)
        Text("Saved privately in app storage. CSV export/share is not yet implemented.", color = amber, fontSize = 11.sp)
    }
    Panel("ERROR / FAULT HISTORY") { Text("Fault decoder not validated. No fault-free claim can be made.", fontSize = 12.sp) }
    Panel("POTENTIAL SAFETY CONCERNS") {
        safetyIssues(setup).forEach { item -> Text("⚠ ${item.title} ›", color = amber, modifier = Modifier.fillMaxWidth().clickable { showIssue(item) }.padding(vertical = 8.dp), fontSize = 12.sp) }
    }
    Panel("TELEMETRY") {
        Text("Raw BLE data received: ${h.notifications} notifications", color = pale)
        Text("Controller voltage, current, RPM and temperatures remain unavailable until the frame parser and CRC/checksum are validated.", color = amber, fontSize = 12.sp)
    }
    Panel("BEFORE / AFTER COMPARISON") { Text("Requires validated measurements and saved ride sessions.", fontSize = 12.sp) }
}

@Composable private fun SetupScreen(saved: Setup, save: (Setup) -> Unit) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var notice by remember { mutableStateOf("") }
    Text("MY SETUP", color = blue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Stored locally. Enter verified manufacturer specifications.", color = pale, fontSize = 12.sp)
    Panel("VEHICLE & CONTROLLER") {
        SetupField("Vehicle name", draft.vehicle) { draft = draft.copy(vehicle = it) }
        SetupField("Controller model", draft.controller) { draft = draft.copy(controller = it) }
    }
    Panel("BATTERY & BMS") {
        SetupField("Battery make/model", draft.battery) { draft = draft.copy(battery = it) }
        SetupField("Battery continuous discharge (A)", draft.batteryLimit, true) { draft = draft.copy(batteryLimit = it) }
        SetupField("BMS make/model", draft.bms) { draft = draft.copy(bms = it) }
        SetupField("BMS continuous discharge (A)", draft.bmsLimit, true) { draft = draft.copy(bmsLimit = it) }
    }
    Panel("MOTOR") {
        SetupField("Motor make/model", draft.motor) { draft = draft.copy(motor = it) }
        SetupField("Verified motor temperature limit (°C)", draft.motorLimit, true) { draft = draft.copy(motorLimit = it) }
    }
    Button(onClick = { save(draft); notice = "My Setup saved locally" }, modifier = Modifier.fillMaxWidth()) { Text("SAVE MY SETUP") }
    Text(notice, color = green, fontSize = 12.sp)
}
@Composable private fun SetupField(label: String, value: String, numeric: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { text -> if (!numeric || (text.length <= 10 && text.all { it.isDigit() || it == '.' })) update(text) },
        label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
