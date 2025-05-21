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
    private var debugMessage by mutableStateOf("正在连接...")

    private lateinit var bluetoothMonitor: BluetoothMonitor
    private lateinit var wifiMonitor: WifiMonitor
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 5

    private lateinit var screenBrightnessMonitor: ScreenBrightnessMonitor

    private lateinit var wifiReceiver: WifiStateReceiver
    private lateinit var bluetoothReceiver: BluetoothDeviceReceiver

    private lateinit var brightnessObserver: BrightnessObserver

    // 用于网络监控的回调
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // 用于控制发送消息频率的变量
    private var lastMessageTime = 0L
    private val messageCooldown = 2000 // 2秒冷却时间

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        enableEdgeToEdge()

        // 请求必要的权限
        requestPermissions()

        // 初始化蓝牙和WiFi监视器
        bluetoothMonitor = BluetoothMonitor(this)
        wifiMonitor = WifiMonitor(this)

        // 初始化屏幕亮度监视器
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

        // 复制证书文件并处理错误情况
        prepareAssetFiles()

        // 注册网络状态变化监听
        registerNetworkCallback()

        // 连接到AWS IoT
        connectToAWSIoT()

        val brightness = Utils.getScreenBrightness(contentResolver)
        Log.d("ScreenBrightness", "当前屏幕亮度: $brightness")

        // 请求写入设置权限
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        // 注册WiFi状态接收器
        wifiReceiver = WifiStateReceiver { status, ssid ->
            sendWifiStatusChangeSafely(status, ssid)
        }
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        // 注册蓝牙设备接收器
        bluetoothReceiver = BluetoothDeviceReceiver { deviceName, status ->
            sendBluetoothDeviceChangeSafely(deviceName, status)
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))

        // 注册亮度变化监听器
        val handler = Handler(Looper.getMainLooper())
        brightnessObserver = BrightnessObserver(this, handler) { brightness ->
            sendBrightnessChangeSafely(brightness)
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            true,
            brightnessObserver
        )

        // 延迟发送初始状态，确保连接稳定
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
            Log.d("AWS-IoT", "开始连接...")
            debugMessage = "正在连接AWS IoT..."

            // 使用设备唯一ID作为客户端ID的一部分，避免冲突
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val clientId = "android-device-$deviceId"

            // 检查证书文件是否存在
            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            if (!certFile.exists() || !keyFile.exists() || !rootCAFile.exists()) {
                Log.e("AWS-IoT", "证书文件缺失，尝试重新复制")
                prepareAssetFiles()
                // 如果文件仍然不存在，则退出
                if (!certFile.exists() || !keyFile.exists() || !rootCAFile.exists()) {
                    debugMessage = "证书文件不存在，无法连接AWS IoT"
                    Log.e("AWS-IoT", "证书文件缺失")
                    return
                }
            }

            mqttManager = AWSIotMqttManager(clientId, customerSpecificEndpoint)

            // 修改连接参数
            mqttManager.setKeepAlive(30)  // 降低为30秒
            mqttManager.setAutoReconnect(true)
            mqttManager.setCleanSession(true)  // 使用干净会话
            mqttManager.setMaxAutoReconnectAttempts(5)
            mqttManager.setReconnectRetryLimits(1000, 30000)  // 最大30秒重连间隔
            mqttManager.setOfflinePublishQueueEnabled(true)  // 启用离线队列

            // 转换证书
            val keyStore = Utils.convertPemToPkcs12(filesDir)

            // 配置SSL上下文
            val password = "temp".toCharArray()
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)

            Log.d("AWS-IoT", "开始MQTT连接...")

            mqttManager.connect(keyStore) { status, throwable ->
                runOnUiThread {
                    when (status) {
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                            Log.d("AWS-IoT", "连接成功")
                            isConnected = true
                            debugMessage = "MQTT客户端已连接"
                            connectionAttempts = 0

                            // 延迟订阅主题
                            delayedSubscribeToTopics()

                            // 发送简单的连接状态消息
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    if (isConnected) {
                                        mqttManager.publishString(
                                            "{\"status\":\"connected\",\"deviceId\":\"$deviceId\"}",
                                            "AMS/device/status",
                                            AWSIotMqttQos.QOS0
                                        )
                                        Log.d("AWS-IoT", "发送连接状态消息成功")
                                    }
                                } catch (e: Exception) {
                                    Log.e("AWS-IoT", "发送连接状态消息失败", e)
                                }
                            }, 2000)  // 等待2秒后发送
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                            Log.e("AWS-IoT", "连接丢失", throwable)
                            isConnected = false
                            debugMessage = "MQTT连接丢失: ${throwable?.message}"

                            // 增加重连间隔，避免频繁重连
                            val reconnectDelay = (Math.min(connectionAttempts, 5) * 1000 + 3000).toLong()
                            connectionAttempts++

                            // 延迟重连
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isConnected) {
                                    connectToAWSIoT()
                                }
                            }, reconnectDelay)
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting -> {
                            Log.w("AWS-IoT", "重新连接中", throwable)
                            debugMessage = "正在重新连接..."
                        }
                        else -> {
                            Log.e("AWS-IoT", "连接失败: $status", throwable)
                            debugMessage = "连接失败: ${throwable?.message}"

                            // 3秒后重试
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
            Log.e("AWS-IoT", "连接过程中出错", e)
            debugMessage = "连接错误: ${e.message}"
            e.printStackTrace()

            // 5秒后重试
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
                    // 使用QOS0订阅主题，降低复杂性
                    mqttManager.subscribeToTopic("AMS/brightness/control", AWSIotMqttQos.QOS0) { topic, data ->
                        try {
                            val message = String(data)
                            Log.d("AWS-IoT", "接收到消息: $message")
                            val json = JSONObject(message)
                            val brightness = json.getInt("screenBrightness")
                            runOnUiThread {
                                setScreenBrightness(brightness)
                            }
                        } catch (e: Exception) {
                            Log.e("AWS-IoT", "处理消息时出错", e)
                        }
                    }
                    Log.d("AWS-IoT", "成功订阅亮度控制主题")
                } catch (e: Exception) {
                    Log.e("AWS-IoT", "订阅主题失败", e)
                }
            }
        }, 3000)  // 等待3秒后再订阅
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("NetworkMonitor", "网络可用")
                if (!isConnected) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runOnUiThread {
                            connectToAWSIoT()
                        }
                    }, 2000)  // 等待2秒确保网络稳定
                }
            }

            override fun onLost(network: Network) {
                Log.d("NetworkMonitor", "网络丢失")
                runOnUiThread {
                    isConnected = false
                    debugMessage = "网络连接丢失"
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d("NetworkMonitor", "网络能力变化，互联网连接: $hasInternet")
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun setScreenBrightness(brightness: Int) {
        try {
            val brightnessValue = (brightness * 2047) / 100 // 将百分比转换为系统亮度值
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
                Log.d("ScreenBrightness", "亮度已设置为: $brightness%")
            } else {
                Log.e("ScreenBrightness", "没有写入设置的权限")
                debugMessage = "无法设置亮度：需要写入设置的权限"
            }
        } catch (e: Exception) {
            Log.e("ScreenBrightness", "设置亮度失败", e)
        }
    }

    // 安全发送消息，包含频率控制和错误处理
    private fun sendMessageSafely(message: String, topic: String) {
        try {
            val currentTime = System.currentTimeMillis()
            if (isConnected && currentTime - lastMessageTime > messageCooldown) {
                mqttManager.publishString(message, topic, AWSIotMqttQos.QOS0)
                lastMessageTime = currentTime
                Log.d("AWS-IoT", "消息发送成功: $message to $topic")
            } else if (!isConnected) {
                Log.w("AWS-IoT", "MQTT未连接，无法发送消息")
            } else {
                Log.d("AWS-IoT", "消息发送频率过高，跳过")
            }
        } catch (e: Exception) {
            Log.e("AWS-IoT", "发送消息失败", e)
            e.printStackTrace()
        }
    }

    private fun sendWifiStatus() {
        try {
            if (isConnected) {
                val isWifiEnabled = wifiMonitor.wifiState.value ?: false
                val connectedWifiSSID = wifiMonitor.connectedWifiSSID.value ?: "未连接"
                val isBluetoothEnabled = bluetoothMonitor.bluetoothState.value ?: false
                val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0
                val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0

                // 创建JSON对象
                val statusJson = JSONObject().apply {
                    put("wifiStatus", if (isWifiEnabled) "ON" else "OFF")
                    put("connectedSSID", connectedWifiSSID)
                    put("bluetoothStatus", if (isBluetoothEnabled) "ON" else "OFF")
                    put("pairedDevicesCount", pairedDevicesCount)
                    put("screenBrightness", brightness)
                    put("timestamp", System.currentTimeMillis())
                }

                // 转换JSON对象为字符串
                val statusMessage = statusJson.toString()

                sendMessageSafely(statusMessage, "AMS/device/status")
            } else {
                debugMessage = "MQTT客户端未连接"
                Log.w("AWS-IoT", "MQTT未连接，无法发送状态")
            }
        } catch (e: Exception) {
            debugMessage = "发送状态错误: ${e.message}"
            Log.e("AWS-IoT", "发送状态时出错", e)
            e.printStackTrace()
        }
    }

    private fun prepareAssetFiles() {
        // 创建assets目录用于存放自定义文件
        val certificateDir = File(filesDir, "certificates")
        if (!certificateDir.exists()) {
            certificateDir.mkdirs()
        }

        // 如果找不到证书文件，创建默认的空文件
        // 实际开发中，这些文件应从AWS IoT控制台下载
        val certFile = File(filesDir, "cc9.cert.pem")
        val keyFile = File(filesDir, "cc9.private.key")
        val rootCAFile = File(filesDir, "root-CA.crt")

        try {
            // 尝试从assets复制
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

                    // 如果文件已存在则删除
                    if (outputFile.exists()) {
                        outputFile.delete()
                        Log.d("FileCopy", "删除已存在的文件: $fileName")
                    }

                    val outputStream = outputFile.outputStream()
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()

                    Log.d("FileCopy", "成功复制文件 $fileName (${outputFile.length()} 字节)")
                } catch (e: Exception) {
                    Log.e("FileCopy", "复制文件失败: $fileName", e)
                    debugMessage = "文件复制错误: $fileName - ${e.message}"

                    // 创建空文件便于调试
                    if (!File(filesDir, fileName).exists()) {
                        File(filesDir, fileName).createNewFile()
                        Log.d("FileCopy", "创建空文件: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileCopy", "文件处理过程中出错", e)
            debugMessage = "文件处理错误: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 断开MQTT连接
        if (::mqttManager.isInitialized) {
            try {
                mqttManager.disconnect()
                Log.d("AWS-IoT", "MQTT连接已断开")
            } catch (e: Exception) {
                Log.e("AWS-IoT", "断开MQTT连接时出错", e)
            }
        }

        // 清理蓝牙资源
        bluetoothMonitor.cleanup()

        // 注销广播接收器
        try {
            unregisterReceiver(wifiReceiver)
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "注销接收器时出错", e)
        }

        // 注销内容观察器
        try {
            contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (e: Exception) {
            Log.e("MainActivity", "注销内容观察器时出错", e)
        }

        // 注销网络回调
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("MainActivity", "注销网络回调时出错", e)
        }
    }

    // 安全发送WiFi状态变化
    private fun sendWifiStatusChangeSafely(wifiStatus: String, connectedSSID: String) {
        val message = JSONObject().apply {
            put("wifiStatus", wifiStatus)
            put("connectedSSID", connectedSSID)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/wifi")
    }

    // 公开方法，供UI调用
    fun sendWifiStatusChange(wifiStatus: String, connectedSSID: String) {
        sendWifiStatusChangeSafely(wifiStatus, connectedSSID)
    }

    // 安全发送蓝牙设备变化
    private fun sendBluetoothDeviceChangeSafely(deviceName: String, status: String) {
        val message = JSONObject().apply {
            put("deviceName", deviceName)
            put("status", status)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/bluetooth")
    }

    // 公开方法，供UI调用
    fun sendBluetoothDeviceChange(deviceName: String, status: String) {
        sendBluetoothDeviceChangeSafely(deviceName, status)
    }

    // 安全发送亮度变化
    private fun sendBrightnessChangeSafely(screenBrightness: Int) {
        val message = JSONObject().apply {
            put("screenBrightness", screenBrightness)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/brightness")
    }

    // 公开方法，供UI调用
    fun sendBrightnessChange(screenBrightness: Int) {
        sendBrightnessChangeSafely(screenBrightness)
    }

    // 发送蓝牙状态变化
    fun sendBluetoothDeviceChange(bluetoothStatus: String, pairedDevicesCount: Int) {
        val message = JSONObject().apply {
            put("bluetoothStatus", bluetoothStatus)
            put("pairedDevicesCount", pairedDevicesCount)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessageSafely(message.toString(), "AMS/bluetooth")
    }

    // 发送初始状态
    private fun sendInitialStatus() {
        try {
            // 获取当前WiFi状态
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiStatus = if (wifiManager.isWifiEnabled) "ON" else "OFF"
            val connectedSSID = wifiManager.connectionInfo.ssid ?: "未知"

            // 获取当前蓝牙状态
            val bluetoothStatus = if (bluetoothMonitor.bluetoothState.value == true) "ON" else "OFF"
            val pairedDevicesCount = bluetoothMonitor.pairedDevices.value?.size ?: 0

            // 获取当前屏幕亮度
            val brightness = screenBrightnessMonitor.screenBrightness.value ?: 0

            // 创建初始化状态JSON
            val initJson = JSONObject().apply {
                put("wifiStatus", wifiStatus)
                put("connectedSSID", connectedSSID)
                put("bluetoothStatus", bluetoothStatus)
                put("pairedDevicesCount", pairedDevicesCount)
                put("screenBrightness", brightness)
                put("isInitialStatus", true)
                put("timestamp", System.currentTimeMillis())
            }

            // 发送初始化状态
            if (isConnected) {
                sendMessageSafely(initJson.toString(), "AMS/device/init")
                Log.d("MainActivity", "已发送初始状态")
            } else {
                Log.d("MainActivity", "MQTT未连接，未能发送初始状态")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "发送初始状态时出错", e)
        }
    }
}

class WifiStateReceiver(private val onWifiStateChanged: (String, String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectedSSID = wifiManager.connectionInfo.ssid ?: "未知"
            if (networkInfo?.isConnected == true) {
                onWifiStateChanged("Connected", connectedSSID)
            } else {
                onWifiStateChanged("Disconnected", "未连接")
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
                    onBluetoothDeviceChanged(it.name ?: "未知设备", "Connected")
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    onBluetoothDeviceChanged(it.name ?: "未知设备", "Disconnected")
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
            val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            val normalizedBrightness = (brightness * 100) / 2047  // 归一化为百分比
            onBrightnessChanged(normalizedBrightness)
        } catch (e: Exception) {
            Log.e("BrightnessObserver", "获取亮度失败", e)
        }
    }
}