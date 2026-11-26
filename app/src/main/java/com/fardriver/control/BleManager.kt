package com.fardriver.control

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Read-only transport scaffold. Never writes tuning parameters or firmware. */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val onStatus: (String)->Unit, private val onFrame: (ByteArray)->Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val serviceId = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicId = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val scanCallback = object: ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
            if (name.contains("Far", true) || name.contains("BLE", true) || name.contains("JDY", true)) {
                stopScan(); onStatus("Connecting to $name")
                gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; onStatus("Scan failed: $errorCode") }
    }
    private val callback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { onStatus("Connection error: $status"); g.close(); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) { onStatus("Discovering services"); g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { onStatus("Disconnected"); g.close() }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(serviceId)?.getCharacteristic(characteristicId)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) { onStatus("Expected BLE service/characteristic not found"); return }
            val enabled = g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccd)
            if (enabled && descriptor != null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                onStatus("Connected: notification setup requested")
            } else onStatus("Connected; notification setup unavailable")
        }
        @Deprecated("Android 13 callback compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = c.value?.clone() ?: byteArrayOf()
            onFrame(data)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onFrame(value.clone()) }
    }
    fun scan() {
        if (scanning) return
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { onStatus("Enable Bluetooth first"); return }
        onStatus("Scanning for compatible BLE devices")
        scanning = true
        adapter.bluetoothLeScanner?.startScan(scanCallback)
        handler.postDelayed({ if (scanning) { stopScan(); onStatus("Scan timed out") } }, 12000)
    }
    private fun stopScan() { if (scanning) { scanning = false; manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } }
    fun close() { stopScan(); gatt?.disconnect(); gatt?.close(); gatt = null }
}
