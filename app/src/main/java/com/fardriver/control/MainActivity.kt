package com.fardriver.control

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * FarDriver Control v0.6: read-only BLE discovery and connection diagnostics.
 * Drop-in replacement retaining the original (Context, status, frame) constructor.
 * Scan lists ALL advertising BLE devices, then lets the user choose manually.
 * This does not implement FarDriver controller commands or parameter writes.
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {
    private data class Seen(val device: BluetoothDevice, val name: String, val rssi: Int)
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val serviceId = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicId = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val seen = linkedMapOf<String, Seen>()
    private val log = ArrayDeque<String>()
    private var scanning = false
    private var closed = false
    private var connecting = false
    private var connected = false
    private var gatt: BluetoothGatt? = null
    private var chosen: BluetoothDevice? = null
    private var retries = 0
    private var userDisconnect = false
    private var connectionTimeout: Runnable? = null
    private var retryTask: Runnable? = null
    private var picker: AlertDialog? = null

    private fun ui(block: () -> Unit) = handler.post { if (!closed) block() }
    private fun event(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(log) {
            if (log.size >= 120) log.removeFirst()
            log.addLast("$timestamp  $message")
        }
        ui { onStatus(message) }
    }
    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || closed) return
            val device = result.device
            val name = result.scanRecord?.deviceName ?: try { device.name } catch (_: Exception) { null }
                ?: "Unnamed BLE device"
            seen[device.address] = Seen(device, name, result.rssi)
            if (seen.size == 1 || seen.size % 5 == 0) event("Found ${seen.size} BLE devices")
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            event("BLE scan failed: $errorCode")
            ui { showPicker() }
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            event("Scan complete: ${seen.size} devices")
            showPicker()
        }
    }

    /** Call from existing MainActivity Scan & Connect button after permission request. */
    fun scan() {
        if (closed) return
        if (!hasPermissions()) { event("Nearby devices permission required"); return }
        if (scanning || connecting) { event("BLE operation already in progress"); return }
        if (connected) { event("Already connected. Disconnect before scanning again"); return }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { event("Enable Bluetooth first"); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { event("BLE scanner unavailable"); return }
        seen.clear()
        userDisconnect = false
        try {
            scanning = true
            scanner.startScan(scanCallback)
            event("Scanning all nearby BLE devices for 10 seconds")
            handler.postDelayed(scanTimeout, 10_000L)
        } catch (e: Exception) {
            scanning = false
            event("Scan error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
    }

    private fun showPicker() {
        val activity = context as? Activity
        if (activity == null || activity.isFinishing || closed) {
            event("Device selection requires an Activity context")
            return
        }
        val devices = seen.values.sortedByDescending { it.rssi }
        if (devices.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("FarDriver BLE scan")
                .setMessage("No advertising BLE devices found. Power the controller and Bluetooth module, move closer, and try again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = devices.map { "${it.name}   ${it.rssi} dBm\n${it.device.address}" }.toTypedArray()
        picker?.dismiss()
        picker = AlertDialog.Builder(activity)
            .setTitle("Select BLE device (${devices.size} found)")
            .setItems(labels) { _, index -> connect(devices[index].device) }
            .setNeutralButton("View connection log") { _, _ -> showLog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLog() {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || closed) return
        val text = synchronized(log) { log.joinToString("\n") }
        AlertDialog.Builder(activity).setTitle("BLE diagnostic log")
            .setMessage(text.ifEmpty { "No events" }).setPositiveButton("Close", null).show()
    }

    private fun connect(device: BluetoothDevice, retry: Boolean = false) {
        if (closed || !hasPermissions()) return
        stopScan()
        cancelTimeout()
        connecting = true
        connected = false
        chosen = device
        if (!retry) retries = 0
        val old = gatt
        gatt = null
        try { old?.close() } catch (_: Exception) { }
        event("Connecting to ${device.address}${if (retry) " (retry $retries/2)" else ""}")
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { connecting = false; event("connectGatt returned null"); return }
            val task = Runnable {
                if (connecting && !closed) {
                    event("GATT connection timed out")
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
                    gatt = null
                    connecting = false
                    scheduleRetry()
                }
            }
            connectionTimeout = task
            handler.postDelayed(task, 15_000L)
        } catch (e: Exception) {
            connecting = false
            event("Connect error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun cancelTimeout() {
        connectionTimeout?.let { handler.removeCallbacks(it) }
        connectionTimeout = null
    }
    private fun scheduleRetry() {
        if (closed || userDisconnect || retries >= 2 || chosen == null) {
            event("Reconnect stopped; scan again to retry")
            return
        }
        retries++
        val delay = 2_000L * retries
        event("Reconnecting in ${delay / 1000} seconds")
        val task = Runnable { chosen?.let { connect(it, retry = true) } }
        retryTask = task
        handler.postDelayed(task, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            cancelTimeout()
            if (closed) { g.close(); return }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                connected = false
                event("GATT error $status")
                try { g.close() } catch (_: Exception) { }
                if (gatt === g) gatt = null
                scheduleRetry()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting = false
                    connected = true
                    retries = 0
                    event("GATT connected; discovering services")
                    try { g.discoverServices() } catch (e: Exception) { event("Discovery error: ${e.message}") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting = false
                    connected = false
                    event("GATT disconnected")
                    try { g.close() } catch (_: Exception) { }
                    if (gatt === g) gatt = null
                    scheduleRetry()
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { event("Service discovery failed: $status"); return }
            val services = g.services
            event("Discovered ${services.size} BLE services")
            services.forEach { service ->
                event("Service ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    event("  Char ${characteristic.uuid} properties=0x${characteristic.properties.toString(16)}")
                }
            }
            val characteristic = g.getService(serviceId)?.getCharacteristic(characteristicId)
            if (characteristic == null) {
                event("Expected FFE0/FFEC not found; inspect diagnostic log")
                ui { showLog() }
                return
            }
            val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!canNotify) { event("FFEC does not support notifications"); return }
            if (!g.setCharacteristicNotification(characteristic, true)) { event("Local notification setup failed"); return }
            val descriptor = characteristic.getDescriptor(cccdId)
            if (descriptor == null) { event("CCCD descriptor missing"); return }
            val success = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            event(if (success) "Notification subscription requested" else "Notification subscription failed")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == cccdId) event(if (status == BluetoothGatt.GATT_SUCCESS) "Notifications enabled" else "CCCD write failed: $status")
        }
        @Deprecated("Compatibility callback for older Android versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.clone() ?: byteArrayOf()
            if (characteristic.uuid == characteristicId && !closed) onFrame(bytes)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == characteristicId && !closed) onFrame(value.clone())
        }
    }

    fun close() {
        if (closed) return
        userDisconnect = true
        stopScan()
        cancelTimeout()
        retryTask?.let { handler.removeCallbacks(it) }
        retryTask = null
        closed = true
        picker?.dismiss()
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
    }
}
