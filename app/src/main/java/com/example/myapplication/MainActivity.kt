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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(innerPadding)
                }
            }
        }

        copyFilesToInternalStorage()
        connectToAWSIoT()

        val brightness = getScreenBrightness(contentResolver)
        Log.d("ScreenBrightness", "Current screen brightness: $brightness")

        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
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
    }

    @Composable
    fun MainScreen(innerPadding: PaddingValues) {
        val brightness by screenBrightnessMonitor.screenBrightness.observeAsState(-1)

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Display screen brightness
            Text(
                text = "Screen Brightness: $brightness",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Observe Bluetooth and WiFi status
            val isBluetoothEnabled by bluetoothMonitor.bluetoothState.observeAsState(false)
            val pairedDevices by bluetoothMonitor.pairedDevices.observeAsState(emptyList())
            val isWifiEnabled by wifiMonitor.wifiState.observeAsState(false)
            val connectedWifiSSID by wifiMonitor.connectedWifiSSID.observeAsState("Not connected")

            // Bluetooth status display
            Text(
                text = if (isBluetoothEnabled) "Bluetooth is turned on" else "Bluetooth is turned off",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Paired device display
            Text(
                text = "Paired device count: ${pairedDevices.size}",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // WiFi status display
            Text(
                text = if (isWifiEnabled) "WiFi is turned on" else "WiFi is turned off",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Current WiFi: $connectedWifiSSID",
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // MQTT send button
            Button(
                onClick = {
                    // Add a short delay before sending WiFi status
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendWifiStatus()
                    }, 500)  // 500ms delay
                },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Send test message")
            }

            // MQTT reconnect button
            Button(
                onClick = { connectToAWSIoT() },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Reconnect MQTT")
            }

            // Debug information display
            Text(
                text = debugMessage,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }

    private fun convertPemToPkcs12(): KeyStore {
        try {
            Log.d("AWS-IoT", "Starting certificate format conversion...")

            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            // Read certificate and private key
            val certPem = certFile.readText()
            val keyPem = keyFile.readText()
            val rootCAPem = rootCAFile.readText()

            // Create KeyStore
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)

            // Convert certificate
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
            val rootCA = cf.generateCertificate(rootCAPem.byteInputStream()) as X509Certificate

            // Convert private key
            val privateKeyPem = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")

            val keySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPem, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(keySpec)

            // Store in KeyStore
            val password = "temp".toCharArray()
            keyStore.setKeyEntry(
                "iot-certificate",
                privateKey,
                password,
                arrayOf(cert, rootCA)
            )
            keyStore.setCertificateEntry("root-ca", rootCA)

            Log.d("AWS-IoT", "Certificate conversion successful")
            return keyStore

        } catch (e: Exception) {
            Log.e("AWS-IoT", "Certificate conversion failed", e)
            throw e
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
            val keyStore = convertPemToPkcs12()

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

                            // Delay 1 second before subscribing to topic to ensure connection is fully established
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    mqttManager.subscribeToTopic(
                                        "sdk/test/java",
                                        AWSIotMqttQos.QOS1  // Use QOS1 for reliability
                                    ) { topic, data ->
                                        val message = String(data)
                                        Log.d("AWS-IoT", "Received message: $message")
                                    }
                                    Log.d("AWS-IoT", "Topic subscription successful")
                                } catch (e: Exception) {
                                    Log.e("AWS-IoT", "Failed to subscribe to topic", e)
                                    // If subscription fails, retry in 3 seconds
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (isConnected) {
                                            connectToAWSIoT()
                                        }
                                    }, 3000)
                                }
                            }, 1000)
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

                // Create JSON object
                val wifiStatusJson = JSONObject().apply {
                    put("wifiStatus", if (isWifiEnabled) "ON" else "OFF")
                    put("connectedSSID", connectedWifiSSID)
                }

                // Convert JSON object to string
                val wifiStatusMessage = wifiStatusJson.toString()

                mqttManager.publishString(
                    wifiStatusMessage,
                    "sdk/test/java",  // Use the same topic
                    AWSIotMqttQos.QOS1  // Use the same QoS level
                )
                Log.d("AWS-IoT", "Wi-Fi status sent: $wifiStatusMessage")
            } else {
                debugMessage = "MQTT client is not connected."
                Log.w("AWS-IoT", "MQTT not connected, unable to send Wi-Fi status")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending WiFi status: ${e.message}"
            Log.e("AWS-IoT", "Error sending Wi-Fi status", e)
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
    }

    fun getScreenBrightness(contentResolver: ContentResolver): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("ScreenBrightness", "Error getting screen brightness", e)
            -1
        }
    }
}