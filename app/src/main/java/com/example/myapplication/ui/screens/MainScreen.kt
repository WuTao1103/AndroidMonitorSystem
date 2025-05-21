package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.bluetooth.BluetoothMonitor
import com.example.myapplication.wifi.WifiMonitor
import com.example.myapplication.screenbright.ScreenBrightnessMonitor
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.Wifi

@Composable
fun MainScreen(
    bluetoothMonitor: BluetoothMonitor,
    wifiMonitor: WifiMonitor,
    screenBrightnessMonitor: ScreenBrightnessMonitor,
    sendWifiStatus: () -> Unit,
    connectToAWSIoT: () -> Unit,
    sendWifiStatusChange: (String, String) -> Unit,
    sendBluetoothDeviceChange: (String, String) -> Unit,
    sendBrightnessChange: (Int) -> Unit,
    setScreenBrightness: (Int) -> Unit,
    debugMessage: String
) {
    val isBluetoothEnabled by bluetoothMonitor.bluetoothState.observeAsState(false)
    val pairedDevices by bluetoothMonitor.pairedDevices.observeAsState(emptyList())
    val isWifiEnabled by wifiMonitor.wifiState.observeAsState(false)
    val connectedWifiSSID by wifiMonitor.connectedWifiSSID.observeAsState("Not connected")
    val brightness by screenBrightnessMonitor.screenBrightness.observeAsState(0)

    var sliderPosition by remember { mutableStateOf(0f) }
    var showConnectionDetails by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Update slider position when brightness changes
    LaunchedEffect(brightness) {
        sliderPosition = brightness.toFloat()
    }

    // Create status indicator animation
    val connectionIndicatorColor = if (debugMessage.contains("connected")) {
        Color(0xFF4CAF50) // Green for connected
    } else if (debugMessage.contains("connecting")) {
        Color(0xFFFFA000) // Yellow for connecting
    } else {
        Color(0xFFF44336) // Red for disconnected or error
    }

    // Connection indicator animation
    val infiniteTransition = rememberInfiniteTransition()
    val indicatorAlpha by infiniteTransition.animateFloat(
        initialValue = if (debugMessage.contains("connecting")) 0.4f else 0.8f,
        targetValue = if (debugMessage.contains("connecting")) 0.8f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC))))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title and connection status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Android Device Monitor",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    // Connection status indicator
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                connectionIndicatorColor.copy(alpha = indicatorAlpha),
                                CircleShape
                            )
                            .border(1.dp, connectionIndicatorColor, CircleShape)
                            .clickable { showConnectionDetails = !showConnectionDetails }
                    )
                }

                // Connection status details
                AnimatedVisibility(
                    visible = showConnectionDetails,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = debugMessage,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Device status card
        DeviceStatusCard(
            isBluetoothEnabled = isBluetoothEnabled,
            pairedDevicesCount = pairedDevices.size,
            isWifiEnabled = isWifiEnabled,
            connectedWifiSSID = connectedWifiSSID,
            brightness = brightness,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // Brightness control card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Screen Brightness Control",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Brightness value display
                Text(
                    text = "${sliderPosition.toInt()}%",
                    fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // Brightness slider
                Slider(
                    value = sliderPosition,
                    onValueChange = { value ->
                        sliderPosition = value
                    },
                    onValueChangeFinished = {
                        sendBrightnessChange(sliderPosition.toInt())
                        setScreenBrightness(sliderPosition.toInt())
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                // Brightness preset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BrightnessPresetButton(value = 0, current = sliderPosition.toInt()) {
                        sliderPosition = 0f
                        sendBrightnessChange(0)
                        setScreenBrightness(0)
                    }
                    BrightnessPresetButton(value = 25, current = sliderPosition.toInt()) {
                        sliderPosition = 25f
                        sendBrightnessChange(25)
                        setScreenBrightness(25)
                    }
                    BrightnessPresetButton(value = 50, current = sliderPosition.toInt()) {
                        sliderPosition = 50f
                        sendBrightnessChange(50)
                        setScreenBrightness(50)
                    }
                    BrightnessPresetButton(value = 75, current = sliderPosition.toInt()) {
                        sliderPosition = 75f
                        sendBrightnessChange(75)
                        setScreenBrightness(75)
                    }
                    BrightnessPresetButton(value = 100, current = sliderPosition.toInt()) {
                        sliderPosition = 100f
                        sendBrightnessChange(100)
                        setScreenBrightness(100)
                    }
                }
            }
        }

        // Action buttons
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Main action button
            Button(
                onClick = { sendWifiStatus() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Send All Status",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Secondary action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reconnect button
                OutlinedButton(
                    onClick = { connectToAWSIoT() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reconnect MQTT")
                }

                // Settings button - toggle options
                OutlinedButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                    if (!showSettings) {
                        Text("More Options", modifier = Modifier.padding(start = 4.dp))
                    } else {
                        Text("Hide Options", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            // Settings options
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.9f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        ActionButton(
                            text = "Send WiFi Status",
                            onClick = { sendWifiStatusChange(if (isWifiEnabled) "ON" else "OFF", connectedWifiSSID) }
                        )

                        ActionButton(
                            text = "Send Bluetooth Status",
                            onClick = { sendBluetoothDeviceChange(if (isBluetoothEnabled) "ON" else "OFF", pairedDevices.size.toString()) }
                        )

                        ActionButton(
                            text = "Send Brightness Status",
                            onClick = { sendBrightnessChange(sliderPosition.toInt()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceStatusCard(
    isBluetoothEnabled: Boolean,
    pairedDevicesCount: Int,
    isWifiEnabled: Boolean,
    connectedWifiSSID: String,
    brightness: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Device Status",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Bluetooth status
            StatusRow(
                iconPainter = rememberVectorPainter(Icons.Outlined.Bluetooth),
                title = "Bluetooth",
                status = if (isBluetoothEnabled) "Enabled" else "Disabled",
                detail = "Paired devices: $pairedDevicesCount",
                isActive = isBluetoothEnabled
            )

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.LightGray.copy(alpha = 0.5f)
            )

            // WiFi status
            StatusRow(
                iconPainter = rememberVectorPainter(Icons.Outlined.Wifi),
                title = "WiFi",
                status = if (isWifiEnabled) "Enabled" else "Disabled",
                detail = if (isWifiEnabled && connectedWifiSSID != "\"\"") "Connected to: $connectedWifiSSID" else "Not connected",
                isActive = isWifiEnabled
            )

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.LightGray.copy(alpha = 0.5f)
            )

            // Brightness status
            StatusRow(
                iconPainter = rememberVectorPainter(Icons.Outlined.BrightnessHigh),
                title = "Screen Brightness",
                status = "$brightness%",
                detail = when {
                    brightness < 30 -> "Low"
                    brightness < 70 -> "Medium"
                    else -> "High"
                },
                isActive = true
            )
        }
    }
}

@Composable
fun StatusRow(
    iconPainter: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    status: String,
    detail: String,
    isActive: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = title,
            tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(24.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = detail,
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = status,
            fontSize = 14.sp,
            color = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BrightnessPresetButton(
    value: Int,
    current: Int,
    onClick: () -> Unit
) {
    val isSelected = current == value

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.2f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$value",
            color = if (isSelected) Color.White else Color.DarkGray,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}