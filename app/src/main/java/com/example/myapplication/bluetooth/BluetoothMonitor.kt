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
    // 蓝牙适配器
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // 用于观察蓝牙状态变化的LiveData
    private val _bluetoothState = MutableLiveData<Boolean>()
    val bluetoothState: LiveData<Boolean> get() = _bluetoothState

    // 用于观察已配对设备的LiveData
    private val _pairedDevices = MutableLiveData<List<BluetoothDevice>>()
    val pairedDevices: LiveData<List<BluetoothDevice>> get() = _pairedDevices

    // 监听蓝牙状态变化的广播接收器
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothState.value = (state == BluetoothAdapter.STATE_ON)
            }
        }
    }

    // 初始化
    init {
        // 注册蓝牙状态变化的广播接收器
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)

        // 初始化当前蓝牙状态
        _bluetoothState.value = bluetoothAdapter?.isEnabled

        // 加载已配对设备
        loadPairedDevices()
    }

    // 加载已配对设备
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

    // 获取已连接设备的名称
    fun getConnectedDeviceNames(): List<String> {
        val connectedDeviceNames = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevicesSet: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            pairedDevicesSet?.forEach { device ->
                // 检查设备是否连接到特定服务
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

    // 清理资源
    fun cleanup() {
        context.unregisterReceiver(bluetoothStateReceiver)
    }
}