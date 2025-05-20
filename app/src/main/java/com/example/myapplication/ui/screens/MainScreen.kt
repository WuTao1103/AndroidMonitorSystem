package com.example.myapplication.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R
import com.example.myapplication.bluetooth.BluetoothMonitor
import com.example.myapplication.wifi.WifiMonitor
import com.example.myapplication.screenbright.ScreenBrightnessMonitor
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.Color.Companion.LightGray

@Composable
fun MainScreen(
    bluetoothMonitor: BluetoothMonitor,
    wifiMonitor: WifiMonitor,
    screenBrightnessMonitor: ScreenBrightnessMonitor,
    sendWifiStatus: () -> Unit,
    connectToAWSIoT: () -> Unit,
    sendWifiStatusChange: (String, String) -> Unit,
    sendBluetoothDeviceChange: (String, Int) -> Unit,
    sendBrightnessChange: (Int) -> Unit,
    setScreenBrightness: (Int) -> Unit,
    debugMessage: String
) {
    val isBluetoothEnabled by bluetoothMonitor.bluetoothState.observeAsState(false)
    val pairedDevices by bluetoothMonitor.pairedDevices.observeAsState(emptyList())
    val isWifiEnabled by wifiMonitor.wifiState.observeAsState(false)
    val connectedWifiSSID by wifiMonitor.connectedWifiSSID.observeAsState("Not connected")
    val brightness by screenBrightnessMonitor.screenBrightness.observeAsState(-1)
    var sliderPosition by remember { mutableStateOf(brightness.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFB0BEC5), Color(0xFFECEFF1))))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display screen brightness
        Text(
            text = "Screen Brightness: ${sliderPosition.toInt()}%",
            fontSize = 20.sp,
            color = Color(0xFF333333),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Brightness slider
        Slider(
            value = sliderPosition,
            onValueChange = { value ->
                sliderPosition = value
                sendBrightnessChange(value.toInt())
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.padding(vertical = 16.dp)
        )

        // Bluetooth status display
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.Blue.copy(alpha = if (isBluetoothEnabled) 0.5f else 0.2f), CircleShape)
                    .graphicsLayer { shadowElevation = 8.dp.toPx() }
            )
            Text(
                text = if (isBluetoothEnabled) "Bluetooth is turned on" else "Bluetooth is turned off",
                color = Color(0xFF333333),
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }

        // Paired device display
        Text(
            text = "Paired device count: ${pairedDevices.size}",
            color = Color(0xFF333333),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // WiFi status display
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.Green.copy(alpha = if (isWifiEnabled) 0.5f else 0.2f), CircleShape)
                    .graphicsLayer { shadowElevation = 8.dp.toPx() }
            )
            Text(
                text = if (isWifiEnabled) "WiFi is turned on" else "WiFi is turned off",
                color = Color(0xFF333333),
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
        Text(
            text = "Current WiFi: $connectedWifiSSID",
            color = Color(0xFF333333),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Existing buttons
        Button(
            onClick = {
                // Add a short delay before sending WiFi status
                Handler(Looper.getMainLooper()).postDelayed({
                    sendWifiStatus()
                }, 500)  // 500ms delay
            },
            colors = ButtonDefaults.buttonColors(containerColor = LightGray),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Send test message", color = White)
        }

        Button(
            onClick = { connectToAWSIoT() },
            colors = ButtonDefaults.buttonColors(containerColor = LightGray),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Reconnect MQTT", color = White)
        }

        // New circular buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { sendWifiStatusChange(if (isWifiEnabled) "ON" else "OFF", connectedWifiSSID) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = LightGray),
                modifier = Modifier.size(80.dp)
            ) {
                Text("WiFi", color = White)
            }
            Button(
                onClick = { sendBluetoothDeviceChange(if (isBluetoothEnabled) "ON" else "OFF", pairedDevices.size) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = LightGray),
                modifier = Modifier.size(80.dp)
            ) {
                Text("BT", color = White)
            }
            Button(
                onClick = { sendBrightnessChange(sliderPosition.toInt()) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = LightGray),
                modifier = Modifier.size(80.dp)
            ) {
                Text("Bright", color = White)
            }
        }

        // Debug information display
        Text(
            text = debugMessage,
            color = Color(0xFF666666),
            modifier = Modifier.padding(top = 16.dp)
        )

        // Add a button to test screen brightness
        Button(
            onClick = { setScreenBrightness(50) }, // Set brightness to 50%
            colors = ButtonDefaults.buttonColors(containerColor = LightGray),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Test Brightness 50%", color = White)
        }
    }
} 