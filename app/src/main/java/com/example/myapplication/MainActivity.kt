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

class MainActivity : ComponentActivity() {
    private val customerSpecificEndpoint = "aiier2of1blw9-ats.iot.us-east-1.amazonaws.com"
    private val clientId = "android-device-${System.currentTimeMillis()}"  // 使用唯一的客户端ID
    private lateinit var mqttManager: AWSIotMqttManager
    private var isConnected = false
    private var debugMessage by mutableStateOf("正在连接...")

    private lateinit var bluetoothMonitor: BluetoothMonitor
    private lateinit var wifiMonitor: WifiMonitor
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        enableEdgeToEdge()

        // 请求必要的权限
        requestPermissions()

        // 初始化蓝牙和WiFi监控器
        bluetoothMonitor = BluetoothMonitor(this)
        wifiMonitor = WifiMonitor(this)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(innerPadding)
                }
            }
        }

        copyFilesToInternalStorage()
        connectToAWSIoT()
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
        // 观察蓝牙和WiFi状态
        val isBluetoothEnabled by bluetoothMonitor.bluetoothState.observeAsState(false)
        val pairedDevices by bluetoothMonitor.pairedDevices.observeAsState(emptyList())
        val isWifiEnabled by wifiMonitor.wifiState.observeAsState(false)
        val connectedWifiSSID by wifiMonitor.connectedWifiSSID.observeAsState("Not connected")

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 蓝牙状态显示
            Text(
                text = if (isBluetoothEnabled) "蓝牙已开启" else "蓝牙已关闭",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 配对设备显示
            Text(
                text = "配对设备数量: ${pairedDevices.size}",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // WiFi状态显示
            Text(
                text = if (isWifiEnabled) "WiFi已开启" else "WiFi已关闭",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "当前WiFi: $connectedWifiSSID",
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // MQTT发送按钮
            Button(
                onClick = {
                    // 添加短暂延迟后发送 WiFi 状态
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendWifiStatus()
                    }, 500)  // 500ms 延迟
                },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("发送测试消息")
            }

            // MQTT重连按钮
            Button(
                onClick = { connectToAWSIoT() },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("重新连接MQTT")
            }

            // 调试信息显示
            Text(
                text = debugMessage,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }

    private fun convertPemToPkcs12(): KeyStore {
        try {
            Log.d("AWS-IoT", "开始转换证书格式...")

            val certFile = File(filesDir, "cc9.cert.pem")
            val keyFile = File(filesDir, "cc9.private.key")
            val rootCAFile = File(filesDir, "root-CA.crt")

            // 读取证书和私钥
            val certPem = certFile.readText()
            val keyPem = keyFile.readText()
            val rootCAPem = rootCAFile.readText()

            // 创建 KeyStore
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)

            // 转换证书
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
            val rootCA = cf.generateCertificate(rootCAPem.byteInputStream()) as X509Certificate

            // 转换私钥
            val privateKeyPem = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")

            val keySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPem, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(keySpec)

            // 存储到 KeyStore
            val password = "temp".toCharArray()
            keyStore.setKeyEntry(
                "iot-certificate",
                privateKey,
                password,
                arrayOf(cert, rootCA)
            )
            keyStore.setCertificateEntry("root-ca", rootCA)

            Log.d("AWS-IoT", "证书转换成功")
            return keyStore

        } catch (e: Exception) {
            Log.e("AWS-IoT", "证书转换失败", e)
            throw e
        }
    }

    private fun connectToAWSIoT() {
        try {
            Log.d("AWS-IoT", "开始连接...")
            mqttManager = AWSIotMqttManager("sdk-java", customerSpecificEndpoint)

            // 配置连接参数
            mqttManager.setKeepAlive(600)  // 增加保活时间到600秒
            mqttManager.setAutoReconnect(true)
            mqttManager.setCleanSession(false)  // 使用持久会话
            mqttManager.setMaxAutoReconnectAttempts(10)
            mqttManager.setReconnectRetryLimits(1000, 128000)
            mqttManager.setOfflinePublishQueueEnabled(true)

            // 转换证书格式
            val keyStore = convertPemToPkcs12()

            // 配置 SSL 上下文
            val password = "temp".toCharArray()
            val sslContext = SSLContext.getInstance("TLS")
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)
            sslContext.init(kmf.keyManagers, null, null)

            Log.d("AWS-IoT", "开始 MQTT 连接...")

            mqttManager.connect(keyStore) { status, throwable ->
                runOnUiThread {
                    when (status) {
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                            Log.d("AWS-IoT", "连接成功")
                            isConnected = true
                            debugMessage = "MQTT客户端已连接"

                            // 延迟1秒后订阅主题，确保连接完全建立
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    mqttManager.subscribeToTopic(
                                        "sdk/test/java",
                                        AWSIotMqttQos.QOS1  // 使用QOS1提高可靠性
                                    ) { topic, data ->
                                        val message = String(data)
                                        Log.d("AWS-IoT", "收到消息: $message")
                                    }
                                    Log.d("AWS-IoT", "主题订阅成功")
                                } catch (e: Exception) {
                                    Log.e("AWS-IoT", "订阅主题失败", e)
                                    // 如果订阅失败，3秒后重试
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (isConnected) {
                                            connectToAWSIoT()
                                        }
                                    }, 3000)
                                }
                            }, 1000)
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                            Log.e("AWS-IoT", "连接丢失")
                            isConnected = false
                            debugMessage = "MQTT连接丢失: ${throwable?.message}"

                            // 连接丢失后等待3秒再重连
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isConnected) {
                                    connectToAWSIoT()
                                }
                            }, 3000)
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting -> {
                            Log.w("AWS-IoT", "正在重连", throwable)
                            debugMessage = "正在重新连接..."
                        }
                        else -> {
                            Log.e("AWS-IoT", "连接失败: $status", throwable)
                            debugMessage = "连接失败: ${throwable?.message}"
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("AWS-IoT", "连接过程中发生错误", e)
            debugMessage = "连接错误: ${e.message}"
            e.printStackTrace()
        }
    }

    // 修改发送消息的函数，使用QOS1
    private fun sendMessage(message: String) {
        try {
            if (isConnected) {
                mqttManager.publishString(message, "sdk/test/java", AWSIotMqttQos.QOS1)
                Log.d("AWS-IoT", "消息发送成功: $message")
            } else {
                debugMessage = "MQTT client is not connected."
                Log.w("AWS-IoT", "MQTT未连接，无法发送消息")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending message: ${e.message}"
            Log.e("AWS-IoT", "发送消息失败", e)
            e.printStackTrace()
        }
    }
    private fun sendWifiStatus() {
        try {
            if (isConnected) {
                val isWifiEnabled = wifiMonitor.wifiState.value ?: false
                val connectedWifiSSID = wifiMonitor.connectedWifiSSID.value ?: "Not connected"

                // 创建 JSON 对象
                val wifiStatusJson = JSONObject().apply {
                    put("wifiStatus", if (isWifiEnabled) "ON" else "OFF")
                    put("connectedSSID", connectedWifiSSID)
                }

                // 将 JSON 对象转换为字符串
                val wifiStatusMessage = wifiStatusJson.toString()

                mqttManager.publishString(
                    wifiStatusMessage,
                    "sdk/test/java",  // 使用相同的主题
                    AWSIotMqttQos.QOS1  // 使用相同的 QoS 级别
                )
                Log.d("AWS-IoT", "Wi-Fi 状态已发送: $wifiStatusMessage")
            } else {
                debugMessage = "MQTT client is not connected."
                Log.w("AWS-IoT", "MQTT未连接，无法发送 Wi-Fi 状态")
            }
        } catch (e: Exception) {
            debugMessage = "Error sending WiFi status: ${e.message}"
            Log.e("AWS-IoT", "发送 Wi-Fi 状态时出错", e)
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

                // 如果文件已存在，先删除它
                if (outputFile.exists()) {
                    outputFile.delete()
                    Log.d("FileCopy", "删除已存在的文件: $fileName")
                }

                val outputStream = outputFile.outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()

                Log.d("FileCopy", "成功复制文件 $fileName (${outputFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e("FileCopy", "复制文件失败: $fileName", e)
                debugMessage = "文件复制错误: $fileName - ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 断开MQTT连接
        if (::mqttManager.isInitialized) {
            try {
                mqttManager.disconnect()
            } catch (e: Exception) {
                Log.e("AWS-IoT", "断开MQTT连接时出错", e)
            }
        }
        // 清理蓝牙资源
        bluetoothMonitor.cleanup()
    }
}