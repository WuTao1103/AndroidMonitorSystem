package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.bluetooth.BluetoothMonitor
import com.example.myapplication.wifi.WifiMonitor
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothMonitor: BluetoothMonitor
    private lateinit var wifiMonitor: WifiMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request necessary permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ), 1)
        }

        // Initialize BluetoothMonitor
        bluetoothMonitor = BluetoothMonitor(this)

        // Initialize WifiMonitor
        wifiMonitor = WifiMonitor(this)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Observe Bluetooth state and paired devices
                    val isBluetoothEnabled by bluetoothMonitor.bluetoothState.observeAsState(false)
                    val pairedDevices by bluetoothMonitor.pairedDevices.observeAsState(emptyList())

                    // Observe Wi-Fi state and connected SSID
                    val isWifiEnabled by wifiMonitor.wifiState.observeAsState(false)
                    val connectedWifiSSID by wifiMonitor.connectedWifiSSID.observeAsState("Not connected")

                    // Get connected device names
                    val connectedDeviceNames = bluetoothMonitor.getConnectedDeviceNames()

                    // Display Bluetooth and Wi-Fi status
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Greeting(
                            name = if (isBluetoothEnabled) "Bluetooth is ON" else "Bluetooth is OFF"
                        )
                        Text(text = "Connected Devices:")
                        connectedDeviceNames.forEach { deviceName ->
                            Text(text = deviceName)
                        }

                        Text(text = if (isWifiEnabled) "Wi-Fi is ON" else "Wi-Fi is OFF")
                        Text(text = "Connected Wi-Fi: $connectedWifiSSID")
                    }

                    // Connect to a specific Bluetooth device
                    if (pairedDevices.isNotEmpty()) {
                        val device = pairedDevices.first() // 选择第一个已配对设备
                        connectToDevice(device)
                    }
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("Bluetooth", "Device connected")
                    // 设备已连接
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("Bluetooth", "Device disconnected")
                    // 设备已断开连接
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothMonitor.cleanup() // Clean up resources
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}