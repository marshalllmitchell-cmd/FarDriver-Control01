package com.fardriver.control

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * FarDriver Control BLE transport prototype.
 * Read-only: no tuning commands, firmware updates, or controller parameter writes.
 * The caller must request Bluetooth permissions before calling scan().
 * Service and characteristic IDs require validation against the actual ND72530 module.
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var scanning = false
    private var connecting = false
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            report("Scan timed out")
        }
    }

    private fun report(message: String) {
        handler.post { if (!closed) onStatus(message) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || connecting || closed) return
            val name = try {
                result.scanRecord?.deviceName ?: result.device.name ?: ""
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: ""
            }
            // Discovery heuristic only; some FarDriver modules may advertise different names.
            if (name.contains("Far", ignoreCase = true) ||
                name.contains("BLE", ignoreCase = true) ||
                name.contains("JDY", ignoreCase = true)) {
                stopScan()
                connecting = true
                report("Connecting to $name")
                try {
                    gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) {
                        connecting = false
                        report("Unable to start GATT connection")
                    }
                } catch (e: Exception) {
                    connecting = false
                    report("Connection error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            report("BLE scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) { g.close(); return }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                report("GATT connection error ($status)")
                g.close()
                if (gatt === g) gatt = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting = false
                    report("Connected; discovering services")
                    try { g.discoverServices() } catch (e: Exception) {
                        report("Service discovery failed: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting = false
                    report("Disconnected")
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Service discovery error ($status)")
                return
            }
            val characteristic = g.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                report("Expected FFE0/FFEC characteristic not found")
                return
            }
            val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!supportsNotify) {
                report("FFEC does not advertise notifications")
                return
            }
            if (!g.setCharacteristicNotification(characteristic, true)) {
                report("Failed to enable local notifications")
                return
            }
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) {
                report("Notification descriptor missing")
                return
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            report(if (started) "Connected; enabling notifications" else "Failed to request notifications")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == cccdUuid) {
                report(if (status == BluetoothGatt.GATT_SUCCESS) "Connected; notifications enabled" else "Notification setup error ($status)")
            }
        }

        @Deprecated("Compatibility with Android versions before 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value?.clone() ?: byteArrayOf()
            if (characteristic.uuid == characteristicUuid && !closed) onFrame(data)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == characteristicUuid && !closed) onFrame(value.clone())
        }
    }

    fun scan() {
        if (closed || scanning || connecting) return
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            report("Enable Bluetooth first")
            return
        }
        if (gatt != null) {
            report("Already connected or connection pending; restart app to rescan")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            report("BLE scanner unavailable")
            return
        }
        try {
            scanning = true
            scanner.startScan(scanCallback)
            report("Scanning for FarDriver BLE modules")
            handler.postDelayed(scanTimeout, 12000L)
        } catch (e: Exception) {
            scanning = false
            report("Scan error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
    }

    fun close() {
        if (closed) return
        stopScan()
        closed = true
        connecting = false
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
    }
}
