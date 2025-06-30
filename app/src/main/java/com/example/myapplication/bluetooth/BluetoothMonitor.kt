package com.example.myapplication.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.util.Log
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback

class BluetoothMonitor(private val context: Context) {
    // Bluetooth adapter
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // LiveData for observing Bluetooth state changes
    private val _bluetoothState = MutableLiveData<Boolean>()
    val bluetoothState: LiveData<Boolean> get() = _bluetoothState

    // LiveData for observing paired devices
    private val _pairedDevices = MutableLiveData<List<BluetoothDevice>>()
    val pairedDevices: LiveData<List<BluetoothDevice>> get() = _pairedDevices

    // Broadcast receiver for monitoring Bluetooth state changes
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothState.value = (state == BluetoothAdapter.STATE_ON)
            }
        }
    }

    // Initialize
    init {
        // Register broadcast receiver for Bluetooth state changes
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)

        // Initialize current Bluetooth state
        _bluetoothState.value = bluetoothAdapter?.isEnabled

        // Load paired devices
        loadPairedDevices()
    }

    // Load paired devices
    private fun loadPairedDevices() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevicesSet: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices

            _pairedDevices.value = pairedDevicesSet?.toList() ?: emptyList()
            Log.d("BluetoothMonitor", "Paired devices: ${_pairedDevices.value?.size}")
        } else {
            Log.w("BluetoothMonitor", "Bluetooth connect permission not granted")
            _pairedDevices.value = emptyList()
        }
    }

    // Get connected device name
    fun getConnectedDeviceNames(): List<String> {
        val connectedDeviceNames = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevicesSet: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            pairedDevicesSet?.forEach { device ->
                // Check if device is connected to specific service
                if (isDeviceConnected(device)) {
                    connectedDeviceNames.add(device.name)
                }
            }
        }
        Log.d("BluetoothMonitor", "Connected devices: ${connectedDeviceNames.size}")
        return connectedDeviceNames
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        var isConnected = false
        try {
            val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        isConnected = true
                        gatt?.disconnect()
                    }
                }
            })
            gatt?.close()
        } catch (e: SecurityException) {
            Log.e("BluetoothMonitor", "Permission error: ${e.message}")
        }
        return isConnected
    }

    // Cleanup resources
    fun cleanup() {
        context.unregisterReceiver(bluetoothStateReceiver)
    }
}