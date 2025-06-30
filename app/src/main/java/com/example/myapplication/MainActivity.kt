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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.myapplication.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    private val customerSpecificEndpoint = "aiier2of1blw9-ats.iot.us-east-1.amazonaws.com"
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

    // Callback for network monitoring
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Variable to control message sending frequency
    private var lastMessageTime = 0L
    private val messageCooldown = 2000 // 2 seconds cooldown time

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
                        sendWifiStatusChange = { status, ssid ->
                            sendWifiStatusChange(
                                status,
                                ssid
                            )
                        },
                        sendBluetoothDeviceChange = { deviceName, status ->
                            sendBluetoothDeviceChange(
                                deviceName,
                                status
                            )
                        },
                        sendBrightnessChange = { brightness -> sendBrightnessChange(brightness) },
                        setScreenBrightness = { brightness -> setScreenBrightness(brightness) },
                        debugMessage = debugMessage
                    )
                }
            }
        }

        // Copy certificate files and handle errors
        prepareAssetFiles()

        // Register network state change listener
        registerNetworkCallback()

        // Connect to AWS IoT
        connectToAWSIoT()

        val brightness = Utils.getScreenBrightness(contentResolver)
        Log.d("ScreenBrightness", "Current screen brightness: $brightness")

        // Request write settings permission
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        // Register WiFi state receiver
        wifiReceiver = WifiStateReceiver { status, ssid ->
            sendWifiStatusChangeSafely(status, ssid)
        }
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        // Register Bluetooth device receiver
        bluetoothReceiver = BluetoothDeviceReceiver { deviceName, status ->
            sendBluetoothDeviceChangeSafely(deviceName, status)
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))

        // Register brightness change listener
        val handler = Handler(Looper.getMainLooper())
        brightnessObserver = BrightnessObserver(this, handler) { brightness ->
            sendBrightnessChangeSafely(brightness)
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            true,
            brightnessObserver
        )

        // Delay sending initial status to ensure connection stability
        Handler(Looper.getMainLooper()).postDelayed({
            sendInitialStatus()
        }, 5000)
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

        val permissionsToRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, 1)
        }
    }

    private fun connectToAWSIoT() {
        try {
            Log.d("AWS-IoT", "Starting connection...")
            debugMessage = "Connecting to AWS IoT..."

            // Use device unique ID as part of client ID to avoid conflicts
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val clientId = "android-device-$deviceId"

            // Check if certificate files exist
            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            if (!certFile.exists() || !keyFile.exists() || !rootCAFile.exists()) {
                Log.e("AWS-IoT", "Certificate files missing, attempting to re-copy")
                prepareAssetFiles()
                // If file still doesn't exist, exit
                if (!certFile.exists() || !keyFile.exists() || !rootCAFile.exists()) {
                    debugMessage = "Certificate files not found, unable to connect to AWS IoT"
                    Log.e("AWS-IoT", "Certificate files missing")
                    return
                }
            }

            mqttManager = AWSIotMqttManager(clientId, customerSpecificEndpoint)

            // Modify connection parameters
            mqttManager.setKeepAlive(30)  // Reduce to 30 seconds
            mqttManager.setAutoReconnect(true)
            mqttManager.setCleanSession(true)  // Use clean session
            mqttManager.setMaxAutoReconnectAttempts(5)
            mqttManager.setReconnectRetryLimits(1000, 30000)  // Max 30 seconds reconnect interval
            mqttManager.setOfflinePublishQueueEnabled(true)  // Enable offline queue

            // Convert certificates
            val keyStore = Utils.convertPemToPkcs12(filesDir)

            // Configure SSL context
            val password = "temp".toCharArray()
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)

            Log.d("AWS-IoT", "Starting MQTT connection...")

            mqttManager.connect(keyStore) { status, throwable ->
                runOnUiThread {
                    when (status) {
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                            Log.d("AWS-IoT", "Connection successful")
                            isConnected = true
                            debugMessage = "MQTT client connected"
                            connectionAttempts = 0

                            // Delay subscribing to topics
                            delayedSubscribeToTopics()

                            // Send a simple connection status message
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    if (isConnected) {
                                        mqttManager.publishString(
                                            "{\"status\":\"connected\",\"deviceId\":\"$deviceId\"}",
                                            "AMS/device/status",
                                            AWSIotMqttQos.QOS0
                                        )
                                        Log.d(
                                            "AWS-IoT",
                                            "Sent connection status message successfully"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("AWS-IoT", "Failed to send connection status message", e)
                                }
                            }, 2000)  // Wait 2 seconds before sending
                        }

                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                            Log.e("AWS-IoT", "Connection lost", throwable)
                            isConnected = false
                            debugMessage = "MQTT connection lost: ${throwable?.message}"

                            // Increase reconnect interval to avoid frequent reconnections
                            val reconnectDelay =
                                (Math.min(connectionAttempts, 5) * 1000 + 3000).toLong()
                            connectionAttempts++

                            // Delay reconnection
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isConnected) {
                                    connectToAWSIoT()
                                }
                            }, reconnectDelay)
                        }

                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting -> {
                            Log.w("AWS-IoT", "Reconnecting", throwable)
                            debugMessage = "Reconnecting..."
                        }

                        else -> {
                            Log.e("AWS-IoT", "Connection failed: $status", throwable)
                            debugMessage = "Connection failed: ${throwable?.message}"

                            // Retry after 3 seconds
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isConnected && connectionAttempts < maxConnectionAttempts) {
                                    connectionAttempts++
                                    connectToAWSIoT()
                                }
                            }, 3000)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AWS-IoT", "Error during connection", e)
            debugMessage = "Connection error: ${e.message}"
            e.printStackTrace()

            // Retry after 5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isConnected && connectionAttempts < maxConnectionAttempts) {
                    connectionAttempts++
                    connectToAWSIoT()
                }
            }, 5000)
        }
    }

    private fun delayedSubscribeToTopics() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isConnected) {
                try {
                    // Use QOS0 to subscribe to topics, reduce complexity
                    mqttManager.subscribeToTopic(
                        "AMS/brightness/control",
                        AWSIotMqttQos.QOS0
                    ) { topic, data ->
                        try {
                            val message = String(data)
                            Log.d("AWS-IoT", "Received message: $message")
                            val json = JSONObject(message)
                            val brightness = json.getInt("screenBrightness")
                            runOnUiThread {
                                setScreenBrightness(brightness)
                            }
                        } catch (e: Exception) {
                            Log.e("AWS-IoT", "Error processing message", e)
                        }
                    }
                    Log.d("AWS-IoT", "Successfully subscribed to brightness control topic")
                } catch (e: Exception) {
                    Log.e("AWS-IoT", "Failed to subscribe to topic", e)
                }
            }
        }, 3000)  // Wait 3 seconds before subscribing
    }

    private fun registerNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("NetworkMonitor", "Network available")
                if (!isConnected) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runOnUiThread {
                            connectToAWSIoT()
                        }
                    }, 2000)  // Wait 2 seconds to ensure network stability
                }
            }

            override fun onLost(network: Network) {
                Log.d("NetworkMonitor", "Network lost")
                runOnUiThread {
                    isConnected = false
                    debugMessage = "Network connection lost"
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet =
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d(
                    "NetworkMonitor",
                    "Network capabilities changed, internet connection: $hasInternet"
                )
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun setScreenBrightness(brightness: Int) {
        try {
            val brightnessValue = (brightness * 2047) / 100 // Convert percentage to system brightness value
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightnessValue
                )
                Log.d("ScreenBrightness", "Brightness set to: $brightness%")
            } else {
                Log.e("ScreenBrightness", "No permission to write settings")
                debugMessage = "Cannot set brightness: No permission to write settings"
            }
        } catch (e: Exception) {
            Log.e("ScreenBrightness", "Failed to set brightness", e)
        }
    }

    // Safely send message with frequency control and error handling
    private fun sendMessageSafely(message: String, topic: String) {
        try {
            val currentTime = System.currentTimeMillis()
            if (isConnected && currentTime - lastMessageTime > messageCooldown) {
                mqttManager.publishString(message, topic, AWSIotMqttQos.QOS0)
                lastMessageTime = currentTime
                Log.d("AWS-IoT", "Message sent successfully: $message to $topic")
            } else if (!isConnected) {
                Log.w("AWS-IoT", "MQTT not connected, unable to send message")
            } else {
                Log.d("AWS-IoT", "Message send frequency too high, skipping")
            }
        } catch (e: Exception) {
            Log.e("AWS-IoT", "Error sending message", e)
            e.printStackTrace()
        }
    }

    private fun sendWifiStatus() {
        try {
            if (isConnected) {
                val isWifiEnabled = wifiMonitor.wifiState.value ?: false
                val connectedSSID = wifiMonitor.connectedWifiSSID.value ?: "Not connected"
                val isBluetoothEnabled = bluetoothMonitor.bluetoothState.value ?: false
                val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0
                val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0

                // Create JSON object
                val statusJson = JSONObject().apply {
                    put("wifiStatus", if (isWifiEnabled) "ON" else "OFF")
                    put("connectedSSID", connectedSSID)
                    put("bluetoothStatus", if (isBluetoothEnabled) "ON" else "OFF")
                    put("pairedDevicesCount", pairedDevicesCount)
                    put("screenBrightness", brightness)
                    put("timestamp", System.currentTimeMillis())
                }

                // Convert JSON object to string
                val statusMessage = statusJson.toString()

                sendMessageSafely(statusMessage, "AMS/device/status")
            } else {
                debugMessage = "MQTT client not connected"
                Log.w("AWS-IoT", "MQTT not connected, unable to send status")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending status: ${e.message}"
            Log.e("AWS-IoT", "Error sending status", e)
            e.printStackTrace()
        }
    }

    private fun prepareAssetFiles() {
        // Create assets directory for custom files
        val certificateDir = File(filesDir, "certificates")
        if (!certificateDir.exists()) {
            certificateDir.mkdirs()
        }

        // If certificate files are not found, create default empty files
        // In actual development, these files should be downloaded from AWS IoT console
        val certFile = File(filesDir, "cc9.cert.pem")
        val keyFile = File(filesDir, "cc9.private.key")
        val rootCAFile = File(filesDir, "root-CA.crt")

        try {
            // Try copying from assets
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

                    // If file exists, delete it
                    if (outputFile.exists()) {
                        outputFile.delete()
                        Log.d("FileCopy", "Deleted existing file: $fileName")
                    }

                    val outputStream = outputFile.outputStream()
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()

                    Log.d(
                        "FileCopy",
                        "Successfully copied file $fileName (${outputFile.length()} bytes)"
                    )
                } catch (e: Exception) {
                    Log.e("FileCopy", "Failed to copy file: $fileName", e)
                    debugMessage = "File copy error: $fileName - ${e.message}"

                    // Create empty file for debugging
                    if (!File(filesDir, fileName).exists()) {
                        File(filesDir, fileName).createNewFile()
                        Log.d("FileCopy", "Created empty file: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileCopy", "Error during file handling", e)
            debugMessage = "File copy error: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect MQTT connection
        if (::mqttManager.isInitialized) {
            try {
                mqttManager.disconnect()
                Log.d("AWS-IoT", "MQTT connection disconnected")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "Error disconnecting MQTT", e)
            }
        }

        // Clean up Bluetooth resources
        bluetoothMonitor.cleanup()

        // Unregister broadcast receivers
        try {
            unregisterReceiver(wifiReceiver)
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }

        // Unregister content observer
        try {
            contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering content observer", e)
        }

        // Unregister network callback
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering network callback", e)
        }
    }

    // Safely send WiFi status change
    private fun sendWifiStatusChangeSafely(wifiStatus: String, connectedSSID: String) {
        val message = JSONObject().apply {
            put("wifiStatus", wifiStatus)
            put("connectedSSID", connectedSSID)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/wifi")
    }

    // Public method for UI to call
    fun sendWifiStatusChange(wifiStatus: String, connectedSSID: String) {
        sendWifiStatusChangeSafely(wifiStatus, connectedSSID)
    }

    // Safely send Bluetooth device change
    private fun sendBluetoothDeviceChangeSafely(deviceName: String, status: String) {
        val message = JSONObject().apply {
            put("deviceName", deviceName)
            put("status", status)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/bluetooth")
    }

    // Public method for UI to call
    fun sendBluetoothDeviceChange(deviceName: String, status: String) {
        sendBluetoothDeviceChangeSafely(deviceName, status)
    }

    // Safely send brightness change
    private fun sendBrightnessChangeSafely(screenBrightness: Int) {
        val message = JSONObject().apply {
            put("screenBrightness", screenBrightness)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/brightness")
    }

    // Public method for UI to call
    fun sendBrightnessChange(screenBrightness: Int) {
        sendBrightnessChangeSafely(screenBrightness)
    }

    // Send Bluetooth status change
    fun sendBluetoothDeviceChange(bluetoothStatus: String, pairedDevicesCount: Int) {
        val message = JSONObject().apply {
            put("bluetoothStatus", bluetoothStatus)
            put("pairedDevicesCount", pairedDevicesCount)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/bluetooth")
    }

    // Send initial status
    private fun sendInitialStatus() {
        try {
            // Get current WiFi status
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiStatus = if (wifiManager.isWifiEnabled) "ON" else "OFF"
            val connectedSSID = wifiManager.connectionInfo.ssid ?: "Unknown"

            // Get current Bluetooth status
            val bluetoothStatus = if (bluetoothMonitor.bluetoothState.value == true) "ON" else "OFF"
            val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0

            // Get current screen brightness
            val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0

            // Create initial status JSON
            val initJson = JSONObject().apply {
                put("wifiStatus", wifiStatus)
                put("connectedSSID", connectedSSID)
                put("bluetoothStatus", bluetoothStatus)
                put("pairedDevicesCount", pairedDevicesCount)
                put("screenBrightness", brightness)
                put("isInitialStatus", true)
                put("timestamp", System.currentTimeMillis())
            }

            // Send initial status
            if (isConnected) {
                sendMessageSafely(initJson.toString(), "AMS/device/init")
                Log.d("MainActivity", "Initial status sent")
            } else {
                Log.d("MainActivity", "MQTT not connected, unable to send initial status")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sending initial status", e)
        }
    }
}

class WifiStateReceiver(private val onWifiStateChanged: (String, String) -> Unit) :
    BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            val wifiManager =
                context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectedSSID = wifiManager.connectionInfo.ssid ?: "Unknown"
            if (networkInfo?.isConnected == true) {
                onWifiStateChanged("Connected", connectedSSID)
            } else {
                onWifiStateChanged("Disconnected", "Not connected")
            }
        }
    }
}

class BluetoothDeviceReceiver(private val onBluetoothDeviceChanged: (String, String) -> Unit) :
    BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    onBluetoothDeviceChanged(it.name ?: "Unknown device", "Connected")
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    onBluetoothDeviceChanged(it.name ?: "Unknown device", "Disconnected")
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
        try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                -1
            )
            val normalizedBrightness = (brightness * 100) / 2047  // Normalize to percentage
            onBrightnessChanged(normalizedBrightness)
        } catch (e: Exception) {
            Log.e("BrightnessObserver", "Failed to get brightness", e)
        }
    }
}