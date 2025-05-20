package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.bluetooth.BluetoothMonitor
import com.example.myapplication.wifi.WifiMonitor
import com.example.myapplication.screenbright.ScreenBrightnessMonitor

import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import java.io.File
import java.security.KeyStore
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import android.content.ContentResolver
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.content.BroadcastReceiver
import android.content.Context
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import android.database.ContentObserver
import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.myapplication.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    private val customerSpecificEndpoint = "aiier2of1blw9-ats.iot.us-east-1.amazonaws.com"
    private val clientId = "android-device-${System.currentTimeMillis()}"  // Use a unique client ID
    private lateinit var mqttManager: AWSIotMqttManager
    private var isConnected = false
    private var debugMessage by mutableStateOf("Connecting...")

    private lateinit var bluetoothMonitor: BluetoothMonitor
    private lateinit var wifiMonitor: WifiMonitor
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 5

    private lateinit var screenBrightnessMonitor: ScreenBrightnessMonitor

    private lateinit var wifiReceiver: WifiStateReceiver
    private lateinit var bluetoothReceiver: BluetoothDeviceReceiver

    private lateinit var brightnessObserver: BrightnessObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        enableEdgeToEdge()

        // Request necessary permissions
        requestPermissions()

        // Initialize Bluetooth and WiFi monitors
        bluetoothMonitor = BluetoothMonitor(this)
        wifiMonitor = WifiMonitor(this)

        // Initialize ScreenBrightnessMonitor
        screenBrightnessMonitor = ScreenBrightnessMonitor(this)

        setContent {
            MyApplicationTheme {
                Surface {
                    MainScreen(
                        bluetoothMonitor = bluetoothMonitor,
                        wifiMonitor = wifiMonitor,
                        screenBrightnessMonitor = screenBrightnessMonitor,
                        sendWifiStatus = { sendWifiStatus() },
                        connectToAWSIoT = { connectToAWSIoT() },
                        sendWifiStatusChange = { status, ssid -> sendWifiStatusChange(status, ssid) },
                        sendBluetoothDeviceChange = { deviceName, status -> sendBluetoothDeviceChange(deviceName, status) },
                        sendBrightnessChange = { brightness -> sendBrightnessChange(brightness) },
                        setScreenBrightness = { brightness -> setScreenBrightness(brightness) },
                        debugMessage = debugMessage
                    )
                }
            }
        }

        copyFilesToInternalStorage()
        connectToAWSIoT()

        val brightness = Utils.getScreenBrightness(contentResolver)
        Log.d("ScreenBrightness", "Current screen brightness: $brightness")

        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        // Register WiFi state receiver
        wifiReceiver = WifiStateReceiver { status, ssid ->
            sendWifiStatusChange(status, ssid)
        }
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        // Register Bluetooth device receiver
        bluetoothReceiver = BluetoothDeviceReceiver { deviceName, status ->
            sendBluetoothDeviceChange(deviceName, status)
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))

        // Register brightness change listener
        val handler = Handler(Looper.getMainLooper())
        brightnessObserver = BrightnessObserver(this, handler) { brightness ->
            sendBrightnessChange(brightness)
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            true,
            brightnessObserver
        )

        // 发送初始状态
        sendInitialStatus()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (!permissions.all {
                ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    private fun connectToAWSIoT() {
        try {
            Log.d("AWS-IoT", "Starting connection...")
            mqttManager = AWSIotMqttManager("sdk-java", customerSpecificEndpoint)

            // Configure connection parameters
            mqttManager.setKeepAlive(600)  // Increase keep-alive time to 600 seconds
            mqttManager.setAutoReconnect(true)
            mqttManager.setCleanSession(false)  // Use persistent session
            mqttManager.setMaxAutoReconnectAttempts(10)
            mqttManager.setReconnectRetryLimits(1000, 128000)
            mqttManager.setOfflinePublishQueueEnabled(true)

            // Convert certificate format
            val keyStore = Utils.convertPemToPkcs12(filesDir)

            // Configure SSL context
            val password = "temp".toCharArray()
            val sslContext = SSLContext.getInstance("TLS")
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)
            sslContext.init(kmf.keyManagers, null, null)

            Log.d("AWS-IoT", "Starting MQTT connection...")

            mqttManager.connect(keyStore) { status, throwable ->
                runOnUiThread {
                    when (status) {
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                            Log.d("AWS-IoT", "Connection successful")
                            isConnected = true
                            debugMessage = "MQTT client is connected"

                            // Subscribe to the brightness control topic
                            mqttManager.subscribeToTopic("AMS/brightness/control", AWSIotMqttQos.QOS1) { topic, data ->
                                val message = String(data)
                                Log.d("AWS-IoT", "Received message: $message")
                                val json = JSONObject(message)
                                val brightness = json.getInt("screenBrightness")
                                setScreenBrightness(brightness)
                            }
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                            Log.e("AWS-IoT", "Connection lost")
                            isConnected = false
                            debugMessage = "MQTT connection lost: ${throwable?.message}"

                            // Wait 3 seconds before trying to reconnect
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isConnected) {
                                    connectToAWSIoT()
                                }
                            }, 3000)
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting -> {
                            Log.w("AWS-IoT", "Reconnecting", throwable)
                            debugMessage = "Reconnecting..."
                        }
                        else -> {
                            Log.e("AWS-IoT", "Connection failed: $status", throwable)
                            debugMessage = "Connection failed: ${throwable?.message}"
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("AWS-IoT", "Error during connection", e)
            debugMessage = "Connection error: ${e.message}"
            e.printStackTrace()
        }
    }

    fun setScreenBrightness(brightness: Int) {
        val brightnessValue = (brightness * 2047) / 100 // Convert percentage to system brightness value
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
    }

    // Modify the send message function to use QOS1
    private fun sendMessage(message: String) {
        try {
            if (isConnected) {
                mqttManager.publishString(message, "sdk/test/java", AWSIotMqttQos.QOS1)
                Log.d("AWS-IoT", "Message sent successfully: $message")
            } else {
                debugMessage = "MQTT client is not connected."
                Log.w("AWS-IoT", "MQTT not connected, unable to send message")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending message: ${e.message}"
            Log.e("AWS-IoT", "Failed to send message", e)
            e.printStackTrace()
        }
    }
    private fun sendWifiStatus() {
        try {
            if (isConnected) {
                val isWifiEnabled = wifiMonitor.wifiState.value ?: false
                val connectedWifiSSID = wifiMonitor.connectedWifiSSID.value ?: "Not connected"
                val isBluetoothEnabled = bluetoothMonitor.bluetoothState.value ?: false
                val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0
                val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0

                // Create JSON object
                val statusJson = JSONObject().apply {
                    put("wifiStatus", if (isWifiEnabled) "ON" else "OFF")
                    put("connectedSSID", connectedWifiSSID)
                    put("bluetoothStatus", if (isBluetoothEnabled) "ON" else "OFF")
                    put("pairedDevicesCount", pairedDevicesCount)
                    put("screenBrightness", brightness)
                }

                // Convert JSON object to string
                val statusMessage = statusJson.toString()

                mqttManager.publishString(
                    statusMessage,
                    "sdk/test/java",  // Use the same topic
                    AWSIotMqttQos.QOS1  // Use the same QoS level
                )
                Log.d("AWS-IoT", "Status sent: $statusMessage")
            } else {
                debugMessage = "MQTT client is not connected."
                Log.w("AWS-IoT", "MQTT not connected, unable to send status")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending status: ${e.message}"
            Log.e("AWS-IoT", "Error sending status", e)
            e.printStackTrace()
        }
    }
    private fun copyFilesToInternalStorage() {
        val assetManager = assets
        val filesToCopy = listOf(
            "cc9.cert.pem",
            "cc9.private.key",
            "root-CA.crt"
        )

        for (fileName in filesToCopy) {
            try {
                val inputStream = assetManager.open(fileName)
                val outputFile = File(filesDir, fileName)

                // Delete the file if it already exists
                if (outputFile.exists()) {
                    outputFile.delete()
                    Log.d("FileCopy", "Deleted existing file: $fileName")
                }

                val outputStream = outputFile.outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()

                Log.d("FileCopy", "Successfully copied file $fileName (${outputFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e("FileCopy", "Failed to copy file: $fileName", e)
                debugMessage = "File copy error: $fileName - ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect MQTT
        if (::mqttManager.isInitialized) {
            try {
                mqttManager.disconnect()
            } catch (e: Exception) {
                Log.e("AWS-IoT", "Error disconnecting MQTT", e)
            }
        }
        // Clean up Bluetooth resources
        bluetoothMonitor.cleanup()
        unregisterReceiver(wifiReceiver)
        unregisterReceiver(bluetoothReceiver)
    }

    fun sendWifiStatusChange(wifiStatus: String, connectedSSID: String) {
        val message = JSONObject().apply {
            put("wifiStatus", wifiStatus)
            put("connectedSSID", connectedSSID)
        }
        if (isConnected) {
            mqttManager.publishString(message.toString(), "AMS/wifi", AWSIotMqttQos.QOS1)
        } else {
            Log.e("MainActivity", "MQTT client is not connected")
        }
    }

    fun sendBluetoothDeviceChange(deviceName: String, status: String) {
        val message = JSONObject().apply {
            put("deviceName", deviceName)
            put("status", status)
        }
        mqttManager.publishString(message.toString(), "AMS/bluetooth", AWSIotMqttQos.QOS1)
    }

    fun sendBrightnessChange(screenBrightness: Int) {
        val message = JSONObject().apply {
            put("screenBrightness", screenBrightness)
        }
        if (isConnected) {
            mqttManager.publishString(message.toString(), "AMS/brightness", AWSIotMqttQos.QOS1)
        } else {
            Log.e("MainActivity", "MQTT client is not connected")
        }
    }

    fun sendBluetoothDeviceChange(bluetoothStatus: String, pairedDevicesCount: Int) {
        val message = JSONObject().apply {
            put("bluetoothStatus", bluetoothStatus)
            put("pairedDevicesCount", pairedDevicesCount)
        }
        if (isConnected) {
            mqttManager.publishString(message.toString(), "AMS/bluetooth", AWSIotMqttQos.QOS1)
        } else {
            Log.e("MainActivity", "MQTT client is not connected")
        }
    }

    private fun sendInitialStatus() {
        // 获取当前 WiFi 状态
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiStatus = if (wifiManager.isWifiEnabled) "ON" else "OFF"
        val connectedSSID = wifiManager.connectionInfo.ssid ?: "Unknown"
        sendWifiStatusChange(wifiStatus, connectedSSID)

        // 获取当前蓝牙状态
        val bluetoothStatus = if (bluetoothMonitor.bluetoothState.value == true) "ON" else "OFF"
        val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0
        sendBluetoothDeviceChange(bluetoothStatus, pairedDevicesCount)

        // 获取当前屏幕亮度
        val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0
        sendBrightnessChange(brightness)
    }

}

class WifiStateReceiver(private val onWifiStateChanged: (String, String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectedSSID = wifiManager.connectionInfo.ssid ?: "Unknown"
            if (networkInfo?.isConnected == true) {
                onWifiStateChanged("Connected", connectedSSID)
            } else {
                onWifiStateChanged("Disconnected", "Not connected")
            }
        }
    }
}

class BluetoothDeviceReceiver(private val onBluetoothDeviceChanged: (String, String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    onBluetoothDeviceChanged(it.name ?: "Unknown", "Connected")
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    onBluetoothDeviceChanged(it.name ?: "Unknown", "Disconnected")
                }
            }
        }
    }
}

class BrightnessObserver(
    private val context: Context,
    handler: Handler,
    private val onBrightnessChanged: (Int) -> Unit
) : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        onBrightnessChanged(brightness)
    }
}