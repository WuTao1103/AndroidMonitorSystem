package com.example.myapplication.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class WifiMonitor(private val context: Context) {

    private val wifiManager: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // LiveData to observe Wi-Fi state changes
    private val _wifiState = MutableLiveData<Boolean>()
    val wifiState: LiveData<Boolean> get() = _wifiState

    // LiveData to observe connected Wi-Fi SSID
    private val _connectedWifiSSID = MutableLiveData<String>()
    val connectedWifiSSID: LiveData<String> get() = _connectedWifiSSID

    init {
        updateWifiState()
        updateConnectedWifiSSID()
    }

    private fun updateWifiState() {
        _wifiState.value = wifiManager.isWifiEnabled
    }

    private fun updateConnectedWifiSSID() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = wifiManager.connectionInfo
            _connectedWifiSSID.value = wifiInfo.ssid.removePrefix("\"").removeSuffix("\"")
        } else {
            _connectedWifiSSID.value = "Not connected"
        }
    }
} 