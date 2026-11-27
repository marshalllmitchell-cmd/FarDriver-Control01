package com.fardriver.control

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val navy=Color(0xFF071725)
private val panel=Color(0xFF102B43)
private val blue=Color(0xFF31AFFF)
private val amber=Color(0xFFFFC06C)
private val pale=Color(0xFFC0D7EA)
private val green=Color(0xFF4CE2AA)
private enum class LinkState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECEIVING, RECONNECTING }
private data class Health(
    val state:LinkState=LinkState.DISCONNECTED, val name:String="Not selected", val rssi:Int?=null,
    val connectedAt:Long=0, val lastPacketAt:Long=0, val notifications:Int=0,
    val recentPackets:List<Long> = emptyList(), val lastHex:String="No notifications", val event:String="Ready",
    val rawLogging:Boolean=false, val logCount:Int=0, val validEnvelopes:Int=0,
    val invalidEnvelopes:Int=0, val lastDecode:String="Waiting for data", val decodedCount:Int=0,
    val values:Map<String,Double> = emptyMap(), val fieldTimes:Map<String,Long> = emptyMap(),
    val disconnects:Int=0, val reconnects:Int=0, val startedAt:Long=0, val maxGapMs:Long=0
)
private fun toHex(data:ByteArray)=data.joinToString(" ") { "%02X".format(it.toInt() and 255) }
private fun timeStamp()=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date())

/** BLE transport for this activity; distinct from the existing BleManager.kt. Read-only. */
@SuppressLint("MissingPermission")
private class TelemetryBle(private val activity:Activity,private val update:(Health)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=activity.getSystemService(BluetoothManager::class.java)
    private val serviceId=UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicId=UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val cccdId=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var h=Health()
    private var gatt:BluetoothGatt?=null
    private var selected:BluetoothDevice?=null
    private val found=linkedMapOf<String,Triple<BluetoothDevice,String,Int>>()
    private var scanning=false
    private var closed=false
    private var retryCount=0
    private var timeout:Runnable?=null
    private var retry:Runnable?=null
    private var scanTimeout:Runnable?=null
    private var logFile:File?=null
    private val buffer=ArrayList<String>()
    private fun emit(value:Health) { h=value; update(value) }
    private fun event(text:String) { emit(h.copy(event=text)) }
    private fun permissions():Boolean = if(Build.VERSION.SDK_INT>=31)
        activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        else activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private val scanner=object:ScanCallback() {
        override fun onScanResult(callbackType:Int,result:ScanResult) {
            if(!scanning || closed) return
            val name=result.scanRecord?.deviceName ?: try { result.device.name } catch (_:Exception) { null } ?: "Unnamed BLE device"
            found[result.device.address]=Triple(result.device,name,result.rssi)
            if(found.size==1 || found.size%5==0) event("Found ${found.size} BLE devices")
        }
        override fun onScanFailed(errorCode:Int) { scanning=false; emit(h.copy(state=LinkState.DISCONNECTED,event="Scan failed $errorCode")) }
    }
    fun scan() {
        if(closed || !permissions()) { event("Bluetooth permission required");return }
        if(scanning || h.state==LinkState.RECEIVING || h.state==LinkState.CONNECTED) { event("Already scanning or connected");return }
        val adapter=manager.adapter
        if(adapter==null || !adapter.isEnabled) { event("Enable Bluetooth");return }
        val ble=adapter.bluetoothLeScanner ?: run { event("Scanner unavailable");return }
        found.clear()
        try {
            scanning=true
            ble.startScan(scanner)
            emit(h.copy(state=LinkState.SCANNING,startedAt=if(h.startedAt==0L) SystemClock.elapsedRealtime() else h.startedAt,event="Scanning all nearby BLE devices"))
            val task=Runnable { stopScan(); showPicker() }
            scanTimeout=task;handler.postDelayed(task,10000L)
        } catch(e:Exception) { scanning=false;event("Scan error: ${e.message}") }
    }
    private fun stopScan() {
        scanTimeout?.let { handler.removeCallbacks(it) };scanTimeout=null
        if(!scanning) return
        scanning=false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scanner) } catch (_:Exception) { }
    }
    private fun showPicker() {
        if(closed || activity.isFinishing) return
        val choices=found.values.sortedByDescending { it.third }
        if(choices.isEmpty()) { event("No BLE devices found");return }
        val labels=choices.map { "${it.second} (${it.third} dBm)\n${it.first.address}" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Select FarDriver Bluetooth device")
            .setItems(labels) { _,index ->
                selected=choices[index].first;retryCount=0
                connect(choices[index].first,choices[index].second,choices[index].third)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun cancelTimeout() { timeout?.let { handler.removeCallbacks(it) };timeout=null }
    private fun connect(device:BluetoothDevice,name:String,rssi:Int?) {
        if(closed) return
        cancelTimeout()
        try { gatt?.close() } catch (_:Exception) { }
        gatt=null
        emit(h.copy(state=if(retryCount>0) LinkState.RECONNECTING else LinkState.CONNECTING,name=name,rssi=rssi,connectedAt=0,lastPacketAt=0,recentPackets=emptyList(),event="Connecting"))
        try {
            gatt=device.connectGatt(activity,false,callback,BluetoothDevice.TRANSPORT_LE)
            val task=Runnable { if(h.state==LinkState.CONNECTING || h.state==LinkState.RECONNECTING) {
                try { gatt?.close() } catch (_:Exception) { };gatt=null; reconnect()
            } }
            timeout=task;handler.postDelayed(task,15000L)
        } catch(e:Exception) { event("Connect error: ${e.message}");reconnect() }
    }
    private fun reconnect() {
        if(closed || retryCount>=2 || selected==null) { emit(h.copy(state=LinkState.DISCONNECTED,event="Disconnected; scan to retry"));return }
        retryCount++
        emit(h.copy(state=LinkState.RECONNECTING,reconnects=h.reconnects+1,event="Reconnect $retryCount/2"))
        val task=Runnable { selected?.let { connect(it,h.name,h.rssi) } }
        retry=task;handler.postDelayed(task,retryCount*2000L)
    }
    private val callback=object:BluetoothGattCallback() {
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
            handler.post {
                if(closed || (gatt!=null && gatt!==g)) { g.close();return@post }
                cancelTimeout()
                if(status!=BluetoothGatt.GATT_SUCCESS) {
                    event("GATT error $status");try { g.close() } catch (_:Exception) { };if(gatt===g) gatt=null;reconnect()
                } else if(newState==BluetoothProfile.STATE_CONNECTED) {
                    retryCount=0
                    emit(h.copy(state=LinkState.CONNECTED,connectedAt=SystemClock.elapsedRealtime(),event="GATT connected"))
                    try { g.discoverServices() } catch(e:Exception) { event("Discovery: ${e.message}") }
                } else if(newState==BluetoothProfile.STATE_DISCONNECTED) {
                    emit(h.copy(disconnects=h.disconnects+1))
                    try { g.close() } catch (_:Exception) { };if(gatt===g) gatt=null;reconnect()
                }
            }
        }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
            handler.post {
                if(status!=BluetoothGatt.GATT_SUCCESS) { event("Discovery error $status");return@post }
                val c=g.getService(serviceId)?.getCharacteristic(characteristicId)
                if(c==null) { event("FFE0/FFEC not found");return@post }
                if(c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY==0) { event("FFEC does not notify");return@post }
                if(!g.setCharacteristicNotification(c,true)) { event("Notification setup failed");return@post }
                val descriptor=c.getDescriptor(cccdId) ?: run { event("CCCD missing");return@post }
                val started=if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothGatt.GATT_SUCCESS
                    else {
                        @Suppress("DEPRECATION")
                        descriptor.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                event(if(started) "Enabling notifications" else "Notification request failed")
            }
        }
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int) {
            handler.post { event(if(status==BluetoothGatt.GATT_SUCCESS) "Notifications enabled" else "CCCD error $status") }
        }
        @Deprecated("Older Android callback")
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes=c.value?.clone() ?: byteArrayOf()
            if(Build.VERSION.SDK_INT<33 && c.uuid==characteristicId) receive(bytes)
        }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) {
            if(c.uuid==characteristicId) receive(value.clone())
        }
        override fun onReadRemoteRssi(g:BluetoothGatt,rssi:Int,status:Int) {
            if(status==BluetoothGatt.GATT_SUCCESS) handler.post { emit(h.copy(rssi=rssi)) }
        }
    }
    private fun receive(data:ByteArray) {
        handler.post {
            if(closed || data.isEmpty()) return@post
            val now=SystemClock.elapsedRealtime()
            val decoded=FarDriverTelemetryDecoder.decode(data)
            val acceptable=decoded.integrity==FarDriverTelemetryDecoder.Integrity.CHECKSUM_OK || decoded.integrity==FarDriverTelemetryDecoder.Integrity.SOURCE_UNVERIFIED
            val values=h.values.toMutableMap()
            val times=h.fieldTimes.toMutableMap()
            if(acceptable) decoded.fields.forEach { (key,value) ->
                if(value.isFinite()) { values[key]=value;times[key]=now }
            }
            val recent=(h.recentPackets+now).filter { now-it<=5000L }.takeLast(200)
            val gap=if(h.lastPacketAt>0) now-h.lastPacketAt else 0L
            emit(h.copy(state=LinkState.RECEIVING,lastPacketAt=now,notifications=h.notifications+1,
                recentPackets=recent,lastHex=toHex(data),event="Receiving BLE notifications",
                validEnvelopes=h.validEnvelopes+if(data.size==16 && (data[0].toInt() and 255)==170) 1 else 0,
                invalidEnvelopes=h.invalidEnvelopes+if(data.size!=16 || (data[0].toInt() and 255)!=170) 1 else 0,
                lastDecode="${decoded.family}: ${decoded.integrity} • ${decoded.fields.keys.joinToString()}",
                decodedCount=h.decodedCount+if(decoded.fields.isNotEmpty() && acceptable) 1 else 0,
                values=values,fieldTimes=times,maxGapMs=maxOf(h.maxGapMs,gap)))
            if(h.rawLogging) {
                buffer.add("${timeStamp()},${data.size},${decoded.frameId?.let { "%02X".format(it) } ?: ""},${decoded.integrity},${toHex(data).replace(" ","")}")
                if(buffer.size>=25) flush()
                emit(h.copy(logCount=h.logCount+1))
            }
        }
    }
    fun tick() {
        if(closed) return
        val now=SystemClock.elapsedRealtime()
        if(h.state==LinkState.RECEIVING && now-h.lastPacketAt>3500L) emit(h.copy(state=LinkState.CONNECTED,event="Waiting for notifications"))
        if(h.state==LinkState.CONNECTED || h.state==LinkState.RECEIVING) try { gatt?.readRemoteRssi() } catch (_:Exception) { }
        if(h.rawLogging) flush()
    }
    fun toggleLog() {
        if(h.rawLogging) { flush();emit(h.copy(rawLogging=false,event="CSV saved")) }
        else try {
            val folder=File(activity.filesDir,"ble_logs").apply { mkdirs() }
            logFile=File(folder,"ble_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.csv")
            logFile!!.writeText("timestamp,bytes,frame_id,integrity,hex\n")
            buffer.clear();emit(h.copy(rawLogging=true,logCount=0,event="Recording raw data"))
        } catch(e:Exception) { event("Log error: ${e.message}") }
    }
    private fun flush() {
        if(buffer.isEmpty()) return
        try { logFile?.appendText(buffer.joinToString("\n",postfix="\n"));buffer.clear() }
        catch(e:Exception) { emit(h.copy(rawLogging=false,event="Log write failed: ${e.message}")) }
    }
    fun fileName()=logFile?.name ?: "fardriver_ble.csv"
    fun filePath()=logFile?.absolutePath ?: "No log created"
    fun export(uri:Uri):Boolean {
        flush();val source=logFile ?: return false
        return try { activity.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { it.copyTo(out) } }!=null }
        catch (_:Exception) { false }
    }
    fun close() {
        if(closed) return
        stopScan();cancelTimeout();retry?.let { handler.removeCallbacks(it) };flush();closed=true
        try { gatt?.disconnect();gatt?.close() } catch (_:Exception) { };gatt=null
    }
}

private data class Setup(val vehicle:String="",val controller:String="ND72530",val battery:String="",val bms:String="",val motor:String="",val batteryLimit:String="",val bmsLimit:String="",val motorLimit:String="")
private fun loadSetup(c:Context):Setup {
    val p=c.getSharedPreferences("setup_v1",Context.MODE_PRIVATE)
    fun read(k:String,default:String="")=p.getString(k,default) ?: default
    return Setup(read("vehicle"),read("controller","ND72530"),read("battery"),read("bms"),read("motor"),read("batteryLimit"),read("bmsLimit"),read("motorLimit"))
}
private fun storeSetup(c:Context,s:Setup) {
    c.getSharedPreferences("setup_v1",Context.MODE_PRIVATE).edit().putString("vehicle",s.vehicle).putString("controller",s.controller)
        .putString("battery",s.battery).putString("bms",s.bms).putString("motor",s.motor)
        .putString("batteryLimit",s.batteryLimit).putString("bmsLimit",s.bmsLimit).putString("motorLimit",s.motorLimit).apply()
}
private data class Issue(val title:String,val reason:String,val action:String)
private fun issues(s:Setup):List<Issue> {
    val b=s.batteryLimit.toDoubleOrNull()?.takeIf { it>0 }
    val m=s.bmsLimit.toDoubleOrNull()?.takeIf { it>0 }
    return listOf(
        if(b==null || m==null) Issue("Battery/BMS limits need verification","Manufacturer-verified continuous discharge limits are missing.","Enter battery and BMS limits in My Setup.")
        else Issue("Battery current compatibility","Saved continuous limits: battery ${b.toInt()} A and BMS ${m.toInt()} A. Actual tune settings have not been read.","Verify manufacturer ratings and controller configuration before tuning."),
        Issue("Motor temperature protection","Saved motor limit: ${s.motorLimit.ifBlank { "unknown" }} °C. Sensor readings and ratings require verification.","Confirm motor temperature sensor and manufacturer's limit."),
        Issue("Regenerative braking","Battery charging limits and controller regen parameters are not verified.","Confirm BMS charging capability before tuning regeneration.")
    )
}
class MainActivity:ComponentActivity() {
    private var client:TelemetryBle?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var h by remember { mutableStateOf(Health()) }
            var setup by remember { mutableStateOf(loadSetup(this@MainActivity)) }
            var exportStatus by remember { mutableStateOf("") }
            val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                exportStatus=if(uri!=null && client?.export(uri)==true) "CSV exported" else "Export cancelled or failed"
            }
            val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                if(result.values.all { it }) client?.scan() else h=h.copy(event="Permissions denied")
            }
            val scan:()->Unit={
                if(client==null) client=TelemetryBle(this@MainActivity) { h=it }
                val required=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                val missing=required.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
                if(missing.isEmpty()) client?.scan() else permissions.launch(missing.toTypedArray())
            }
            var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while(true) { delay(1000);clock=SystemClock.elapsedRealtime();client?.tick() } }
            AppUi(h,clock,setup,{ setup=it;storeSetup(this@MainActivity,it) },scan,{ client?.toggleLog() },
                { client?.filePath() ?: "No log created" },{ exporter.launch(client?.fileName() ?: "fardriver_ble.csv") },exportStatus)
        }
    }
    override fun onDestroy() { client?.close();super.onDestroy() }
}
@Composable private fun AppUi(h:Health,clock:Long,setup:Setup,save:(Setup)->Unit,scan:()->Unit,toggle:()->Unit,path:()->String,export:()->Unit,exportStatus:String) {
    var page by remember { mutableStateOf("Ride") }
    var menu by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf<Issue?>(null) }
    val wide=LocalConfiguration.current.screenWidthDp>=650
    val tabs=listOf("Ride","Tuning","Diagnostics","My Setup")
    val stateText=when(h.state) {
        LinkState.RECEIVING->"Receiving Data";LinkState.CONNECTED->"Connected";LinkState.RECONNECTING->"Reconnecting"
        LinkState.CONNECTING->"Connecting";LinkState.SCANNING->"Scanning";LinkState.DISCONNECTED->"Disconnected"
    }
    val stateColor=when(h.state) {
        LinkState.RECEIVING->blue;LinkState.CONNECTED->green;LinkState.RECONNECTING,LinkState.CONNECTING,LinkState.SCANNING->amber;LinkState.DISCONNECTED->Color(0xFFFF7777)
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=blue,background=navy,surface=panel,onSurface=Color.White)) {
        Column(Modifier.fillMaxSize().background(navy).padding(horizontal=12.dp,vertical=8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(wide) TextButton(onClick={ menu=!menu }) { Text("☰") }
                Text("FARDRIVER",color=blue,fontSize=18.sp,fontWeight=FontWeight.Bold)
                Text(" CONTROL",color=Color.White,fontSize=18.sp)
                Spacer(Modifier.weight(1f));Text("● $stateText",color=stateColor,fontSize=11.sp)
            }
            Row(Modifier.weight(1f)) {
                if(wide && menu) Column(Modifier.width(145.dp)) { tabs.forEach { t->TextButton(onClick={ page=t;menu=false }) { Text(t) } } }
                LazyColumn(Modifier.weight(1f)) { item {
                    when(page) {
                        "Ride"->RideScreen(h,clock)
                        "Tuning"->TuningScreen(setup,{ popup=it },{ page="My Setup" })
                        "Diagnostics"->DiagnosticsScreen(h,clock,scan,toggle,path,export,exportStatus,setup,{ popup=it })
                        else->SetupScreen(setup,save)
                    }
                } }
            }
            if(page=="Ride") Row(Modifier.fillMaxWidth().background(Color(0xFF30261B),RoundedCornerShape(10.dp)).border(1.dp,amber,RoundedCornerShape(10.dp)).padding(8.dp)) {
                Text("⚠  Fault status unavailable: decoder not validated",color=amber,fontSize=12.sp)
            }
            if(!wide) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                tabs.forEach { t->TextButton(onClick={ page=t }) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(when(t) { "Ride"->"⌂";"Tuning"->"☷";"Diagnostics"->"▤";else->"⚙" },color=if(page==t) blue else pale,fontSize=21.sp)
                        Text(t,color=if(page==t) blue else pale,fontSize=11.sp)
                    }
                } }
            }
        }
        popup?.let { p->AlertDialog(onDismissRequest={ popup=null },title={ Text(p.title) },text={ Column {
            Text("MY SETUP: ${setup.controller}",color=amber)
            Spacer(Modifier.height(8.dp));Text("WHY THIS MATTERS",color=blue);Text(p.reason)
            Spacer(Modifier.height(8.dp));Text("WHAT YOU CAN DO",color=blue);Text(p.action)
            Text("Information only. No controller writes.",fontSize=11.sp)
        } },confirmButton={ TextButton(onClick={ popup=null }) { Text("Close") } },dismissButton={ TextButton(onClick={ popup=null;page="My Setup" }) { Text("My Setup") } }) }
    }
}
@Composable private fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=5.dp).background(panel,RoundedCornerShape(12.dp)).border(1.dp,Color(0xFF215575),RoundedCornerShape(12.dp)).padding(11.dp)) {
        Text(title,color=blue,fontSize=14.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp))
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}
private fun fresh(h:Health,clock:Long,key:String):Double? {
    if(h.state!=LinkState.RECEIVING && h.state!=LinkState.CONNECTED) return null
    val timestamp=h.fieldTimes[key] ?: return null
    if(clock-timestamp>5000L) return null
    return h.values[key]
}
private fun reading(h:Health,clock:Long,key:String,decimals:Int=1):String? = fresh(h,clock,key)?.let { "%1$.${decimals}f".format(Locale.US,it) }
@Composable private fun RideScreen(h:Health,clock:Long) {
    Text("RIDE  •  LIVE MONITOR",color=pale,fontSize=12.sp)
    Text("Live values: GraphPage.cs mapping • new-format CRC unverified • not for riding/tuning",color=amber,fontSize=10.sp)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        RideGauge("SPEED","MPH","— km/h",false,Modifier.weight(1f),null)
        RideGauge("MOTOR SPEED","RAW","source units",true,Modifier.weight(1f),reading(h,clock,"motorSpeedRaw",0))
    }
    Spacer(Modifier.height(8.dp));Text("ELECTRICAL",color=blue,fontWeight=FontWeight.Bold,fontSize=14.sp)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        RideMetric("Battery","V",Modifier.weight(1f),reading(h,clock,"batteryVolts"))
        RideMetric("Battery Current","A",Modifier.weight(1f),reading(h,clock,"batteryAmps"))
        RideMetric("Power","kW",Modifier.weight(1f),reading(h,clock,"powerKw",2))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        RideMetric("Phase A","A",Modifier.weight(1f),reading(h,clock,"phaseAAmps"))
        RideMetric("Phase C","A",Modifier.weight(1f),reading(h,clock,"phaseCAmps"))
    }
    Panel("DRIVE STATUS") { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        RideStatus("THROTTLE","— %",Modifier.weight(1f))
        RideStatus("MODE","Unknown",Modifier.weight(1f))
        RideStatus("DIRECTION","Unknown",Modifier.weight(1f))
    } }
    Panel("TEMPERATURE") { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        RideTemperature("Controller",Modifier.weight(1f),fresh(h,clock,"controllerC"))
        RideTemperature("Motor",Modifier.weight(1f),fresh(h,clock,"motorC"))
    } }
}
@Composable private fun RideGauge(label:String,unit:String,secondary:String,tach:Boolean,modifier:Modifier,value:String?) {
    Column(modifier.height(166.dp).background(Brush.verticalGradient(listOf(Color(0xFF17334C),Color(0xFF091B2C))),RoundedCornerShape(16.dp))
        .border(1.dp,Color(0xFF285A79),RoundedCornerShape(16.dp)).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,color=blue,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val d=size.minDimension;val cx=size.width/2f;val cy=size.height/2f;val origin=Offset(cx-d/2f,cy-d/2f)
                drawArc(Color(0xFF365773),140f,260f,false,origin,Size(d,d),style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
                drawArc(if(tach) Color(0xFF91B9D4) else blue,140f,if(tach) 200f else 260f,false,origin,Size(d,d),style=Stroke(3.dp.toPx()))
                if(tach) drawArc(Color(0xFFEF6262),340f,60f,false,origin,Size(d,d),style=Stroke(5.dp.toPx()))
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(190,215,235);textSize=9.sp.toPx();textAlign=android.graphics.Paint.Align.CENTER }
                for(i in 0..12) {
                    val angle=Math.toRadians(140.0+i*260.0/12.0)
                    drawLine(pale,Offset(cx+(d*.39f*cos(angle)).toFloat(),cy+(d*.39f*sin(angle)).toFloat()),Offset(cx+(d*.43f*cos(angle)).toFloat(),cy+(d*.43f*sin(angle)).toFloat()),1.dp.toPx())
                    if(i%2==0) drawContext.canvas.nativeCanvas.drawText((if(tach) i else i*10).toString(),cx+(d*.30f*cos(angle)).toFloat(),cy+(d*.30f*sin(angle)).toFloat()+3.sp.toPx(),paint)
                }
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(value ?: "—",color=Color.White,fontSize=43.sp,fontWeight=FontWeight.Bold)
                Text(unit,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Bold)
                Text(secondary,color=pale,fontSize=10.sp)
            }
        }
    }
}
@Composable private fun RideMetric(label:String,unit:String,modifier:Modifier,value:String?) {
    Column(modifier.padding(top=7.dp).background(panel,RoundedCornerShape(10.dp)).border(1.dp,Color(0xFF235C82),RoundedCornerShape(10.dp)).padding(9.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { RideIcon(label);Spacer(Modifier.width(5.dp));Text(label,color=pale,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
        Spacer(Modifier.height(11.dp));Text("${value ?: "—"} $unit",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable private fun RideIcon(label:String) {
    Canvas(Modifier.size(18.dp)) {
        val w=size.width;val h=size.height
        when(label) {
            "Battery","Battery Current"->{
                drawRoundRect(blue,Offset(w*.08f,h*.25f),Size(w*.76f,h*.55f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),style=Stroke(1.8.dp.toPx()))
                drawRect(blue,Offset(w*.85f,h*.42f),Size(w*.1f,h*.19f))
                if(label=="Battery Current") {
                    drawLine(amber,Offset(w*.52f,h*.26f),Offset(w*.37f,h*.55f),2.dp.toPx())
                    drawLine(amber,Offset(w*.37f,h*.55f),Offset(w*.58f,h*.55f),2.dp.toPx())
                    drawLine(amber,Offset(w*.58f,h*.55f),Offset(w*.44f,h*.78f),2.dp.toPx())
                }
            }
            "Power"->for(i in 0..2) drawRoundRect(blue,Offset(w*(.13f+i*.27f),h*(.65f-i*.2f)),Size(w*.16f,h*(.25f+i*.2f)),androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
            else->{
                val points=listOf(.05f to .55f,.25f to .55f,.35f to .25f,.5f to .8f,.65f to .4f,.8f to .55f,.95f to .55f)
                points.zipWithNext().forEach { (a,b)->drawLine(blue,Offset(w*a.first,h*a.second),Offset(w*b.first,h*b.second),1.8.dp.toPx(),cap=StrokeCap.Round) }
            }
        }
    }
}
@Composable private fun RideStatus(label:String,value:String,modifier:Modifier) {
    Column(modifier.background(Color(0xFF163B55),RoundedCornerShape(9.dp)).padding(7.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            if(label=="THROTTLE") { MotorcycleThrottleIcon();Spacer(Modifier.width(3.dp)) }
            Text(label,color=pale,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp));Text(value,color=if(label=="THROTTLE") Color.White else amber,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=1)
        if(label=="THROTTLE") { Spacer(Modifier.height(6.dp));Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF45657B),RoundedCornerShape(3.dp))) }
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
@Composable private fun RideTemperature(label:String,modifier:Modifier,celsius:Double?) {
    Column(modifier.background(Color(0xFF163B55),RoundedCornerShape(9.dp)).padding(8.dp)) {
        Text((if(label=="Motor") "⚙ " else "▦ ")+label,color=pale,fontSize=11.sp)
        Spacer(Modifier.height(9.dp))
        Text(celsius?.let { "%.0f °F (%.0f °C)".format(Locale.US,it*1.8+32,it) } ?: "— °F (— °C)",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
    }
}

private data class DemoParam(val key:String,val label:String,val unit:String,val min:Int,val max:Int,val initial:Int)
private data class DemoGroup(val title:String,val entries:List<DemoParam>)
private val demoGroups=listOf(
    DemoGroup("Battery & Current",listOf(DemoParam("battery","Battery current limit","A",0,300,120),DemoParam("phase","Phase current limit","A",0,800,350))),
    DemoGroup("Speed Modes",listOf(DemoParam("s1","Speed mode 1","%",0,100,30),DemoParam("s2","Speed mode 2","%",0,100,60),DemoParam("s3","Speed mode 3","%",0,100,100))),
    DemoGroup("Throttle Configuration",listOf(DemoParam("throttle","Throttle response","%",0,100,50))),
    DemoGroup("Regenerative Braking",listOf(DemoParam("regen","Regeneration strength","%",0,100,30))),
    DemoGroup("Motor Configuration",listOf(DemoParam("poles","Motor pole pairs","",1,50,23))),
    DemoGroup("Protection Settings",listOf(DemoParam("lvc","Low voltage cutoff","V",0,120,56))),
    DemoGroup("Advanced Parameters",listOf(DemoParam("field","Field weakening","%",0,100,0)))
)
@Composable private fun TuningScreen(setup:Setup,show:(Issue)->Unit,openSetup:()->Unit) {
    val all=remember { demoGroups.flatMap { it.entries } }
    val values=remember { mutableStateMapOf<String,Int>().apply { all.forEach { put(it.key,it.initial) } } }
    val expanded=remember { mutableStateMapOf<String,Boolean>().apply { demoGroups.forEachIndexed { i,g->put(g.title,i==0) } } }
    var saved by remember { mutableStateOf<Map<String,Int>?>(null) }
    Text("TUNING  •  OFFLINE STAGING",color=blue,fontWeight=FontWeight.Bold)
    Text("Illustrative ranges only. Controller read/write is disabled.",color=amber,fontSize=11.sp)
    Text("${all.count { values[it.key]!=it.initial }} modified demo values",color=pale,fontSize=12.sp)
    demoGroups.forEach { group->Panel(group.title) {
        Text(if(expanded[group.title]==true) "▴ Collapse" else "▾ Expand",color=pale,modifier=Modifier.fillMaxWidth().clickable { expanded[group.title]=expanded[group.title]!=true })
        if(expanded[group.title]==true) group.entries.forEach { p->
            val value=values[p.key] ?: p.initial
            var edit by remember(p.key) { mutableStateOf(false) }
            var input by remember(p.key,edit) { mutableStateOf(value.toString()) }
            Column(Modifier.fillMaxWidth().padding(top=8.dp).background(Color(0xFF193B53),RoundedCornerShape(8.dp)).padding(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(p.label,color=Color.White,fontSize=12.sp,modifier=Modifier.weight(1f))
                    if(value!=p.initial) Text("●",color=amber)
                    if(edit) {
                        OutlinedTextField(input,onValueChange={ if(it.length<=6 && it.all(Char::isDigit)) input=it },modifier=Modifier.width(82.dp),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        TextButton(onClick={ input.toIntOrNull()?.let { values[p.key]=it.coerceIn(p.min,p.max) };edit=false }) { Text("✓") }
                    } else TextButton(onClick={ edit=true }) { Text("$value ${p.unit} ✎",color=blue,fontSize=12.sp) }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={ values[p.key]=(value-1).coerceAtLeast(p.min) },modifier=Modifier.width(43.dp),contentPadding=PaddingValues(0.dp)) { Text("−") }
                    Slider(value.toFloat(),onValueChange={ values[p.key]=it.toInt() },valueRange=p.min.toFloat()..p.max.toFloat(),modifier=Modifier.weight(1f).padding(horizontal=4.dp))
                    OutlinedButton(onClick={ values[p.key]=(value+1).coerceAtMost(p.max) },modifier=Modifier.width(43.dp),contentPadding=PaddingValues(0.dp)) { Text("+") }
                }
                Text("Example range: ${p.min}–${p.max} ${p.unit}",color=amber,fontSize=10.sp)
            }
        }
    } }
    Panel("SAFETY REVIEW • MY SETUP") {
        issues(setup).forEach { issue->Text("⚠ ${issue.title} ›",color=amber,modifier=Modifier.fillMaxWidth().clickable { show(issue) }.padding(vertical=7.dp),fontSize=12.sp) }
        TextButton(onClick=openSetup) { Text("Edit My Setup") }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)) { Text("READ") }
        OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)) { Text("WRITE") }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ saved=values.toMap() },modifier=Modifier.weight(1f)) { Text("SAVE DEMO") }
        OutlinedButton(onClick={ saved?.let { values.putAll(it) } },enabled=saved!=null,modifier=Modifier.weight(1f)) { Text("LOAD DEMO") }
    }
    Text("Demo profiles reset on app restart. No hardware commands sent.",color=pale,fontSize=11.sp)
}
@Composable private fun DiagnosticsScreen(h:Health,clock:Long,scan:()->Unit,toggle:()->Unit,path:()->String,export:()->Unit,exportStatus:String,setup:Setup,show:(Issue)->Unit) {
    val age=if(h.lastPacketAt>0) (clock-h.lastPacketAt).coerceAtLeast(0L)/1000.0 else null
    val duration=if(h.connectedAt>0) (clock-h.connectedAt).coerceAtLeast(0L)/1000L else 0L
    val rate=h.recentPackets.count { clock-it<=5000L }/5.0
    Text("DIAGNOSTICS & ADVANCED MONITORING",color=blue,fontSize=17.sp,fontWeight=FontWeight.Bold)
    Panel("BLUETOOTH CONNECTION") {
        Button(onClick=scan) { Text("Scan & Connect") }
        Text("State: ${h.state.name}",color=if(h.state==LinkState.RECEIVING) green else amber)
        Text("Device: ${h.name}\nRSSI: ${h.rssi?.let { "$it dBm" } ?: "Unavailable"}\nNotifications: ${h.notifications}",fontSize=12.sp)
        Text("Rate: %.1f notifications/sec".format(Locale.US,rate),fontSize=12.sp)
        Text("Last notification: ${age?.let { "%.1f sec ago".format(Locale.US,it) } ?: "None"}",fontSize=12.sp)
        Text("Connected for: ${duration/60}m ${duration%60}s",fontSize=12.sp)
        Text("Event: ${h.event}",color=pale,fontSize=11.sp)
        Text("Last raw notification: ${h.lastHex}",fontSize=11.sp)
    }
    Panel("RAW DATA LOGGER") {
        Button(onClick=toggle) { Text(if(h.rawLogging) "Stop & Save Log" else "Start Raw Logging") }
        Text("Logged notifications: ${h.logCount}\nFile: ${path()}",fontSize=11.sp)
        OutlinedButton(onClick=export,enabled=path()!="No log created") { Text("EXPORT CSV") }
        Text(exportStatus,color=green,fontSize=11.sp)
    }
    Panel("LIVE FARDRIVER TELEMETRY") {
        Text("Mapped packets: ${h.decodedCount}",color=green,fontSize=12.sp)
        Text("Decoder: ${h.lastDecode}",color=pale,fontSize=11.sp)
        val keys=listOf("batteryVolts" to "Battery voltage (V)","batteryAmps" to "Battery current (A)","powerKw" to "Power (kW)",
            "phaseAAmps" to "Phase A (A)","phaseCAmps" to "Phase C (A)","motorSpeedRaw" to "Motor speed (source raw)",
            "controllerC" to "Controller (°C)","motorC" to "Motor (°C)","throttleVolts" to "Throttle voltage (V)")
        keys.forEach { (key,label)->Text("$label: ${reading(h,clock,key,if(key=="powerKw") 2 else 1) ?: "—"}",fontSize=12.sp) }
        Text("Source mapping from GraphPage.cs. New-format CRC not verified. Readings are experimental and must not guide riding or tuning.",color=amber,fontSize=11.sp)
    }
    Panel("FRAME VALIDATION") {
        Text("16-byte AA envelopes: ${h.validEnvelopes}\nOther notifications: ${h.invalidEnvelopes}",fontSize=12.sp)
        Text("Legacy checksum verified only when reported CHECKSUM_OK. New-format trailer remains unverified.",color=amber,fontSize=11.sp)
    }
    Panel("CONNECTION SOAK TEST") {
        val total=if(h.startedAt>0) (clock-h.startedAt).coerceAtLeast(0L)/1000L else 0L
        Text("Elapsed: ${total/60}m ${total%60}s",fontSize=12.sp)
        Text("Disconnects: ${h.disconnects} • Reconnects: ${h.reconnects}",fontSize=12.sp)
        Text("Longest notification gap: ${h.maxGapMs} ms",fontSize=12.sp)
    }
    Panel("ERROR / FAULT HISTORY") { Text("Fault decoding not validated; no fault-free claim can be made.",fontSize=12.sp) }
    Panel("POTENTIAL SAFETY CONCERNS") {
        issues(setup).forEach { issue->Text("⚠ ${issue.title} ›",color=amber,modifier=Modifier.fillMaxWidth().clickable { show(issue) }.padding(vertical=8.dp),fontSize=12.sp) }
    }
    Panel("BEFORE / AFTER COMPARISON") { Text("Requires validated ride measurements.",fontSize=12.sp) }
}
@Composable private fun SetupScreen(saved:Setup,save:(Setup)->Unit) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var notice by remember { mutableStateOf("") }
    Text("MY SETUP",color=blue,fontSize=20.sp,fontWeight=FontWeight.Bold)
    Text("Stored locally. Enter verified manufacturer specifications.",color=pale,fontSize=12.sp)
    Panel("VEHICLE & CONTROLLER") {
        SetupField("Vehicle name",draft.vehicle) { draft=draft.copy(vehicle=it) }
        SetupField("Controller model",draft.controller) { draft=draft.copy(controller=it) }
    }
    Panel("BATTERY & BMS") {
        SetupField("Battery make/model",draft.battery) { draft=draft.copy(battery=it) }
        SetupField("Battery continuous discharge (A)",draft.batteryLimit,true) { draft=draft.copy(batteryLimit=it) }
        SetupField("BMS make/model",draft.bms) { draft=draft.copy(bms=it) }
        SetupField("BMS continuous discharge (A)",draft.bmsLimit,true) { draft=draft.copy(bmsLimit=it) }
    }
    Panel("MOTOR") {
        SetupField("Motor make/model",draft.motor) { draft=draft.copy(motor=it) }
        SetupField("Verified motor temperature limit (°C)",draft.motorLimit,true) { draft=draft.copy(motorLimit=it) }
    }
    Button(onClick={ save(draft);notice="My Setup saved locally" },modifier=Modifier.fillMaxWidth()) { Text("SAVE MY SETUP") }
    Text(notice,color=green,fontSize=12.sp)
}
@Composable private fun SetupField(label:String,value:String,numeric:Boolean=false,update:(String)->Unit) {
    OutlinedTextField(value,onValueChange={ text->if(!numeric || (text.length<=10 && text.all { it.isDigit() || it=='.' })) update(text) },
        label={ Text(label,fontSize=12.sp) },singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=7.dp),
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text))
}
