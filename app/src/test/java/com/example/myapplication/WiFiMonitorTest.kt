import android.content.Context
import com.example.myapplication.wifi.WifiMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class WiFiMonitorTest {

    private lateinit var wifiMonitor: WifiMonitor

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        wifiMonitor = WifiMonitor(context)
    }

    @Test
    fun testWiFiState() {
        val isEnabled = wifiMonitor.wifiState.value
        assertNotNull(isEnabled)
    }

    @Test
    fun testConnectedSSID() {
        val ssid = wifiMonitor.connectedWifiSSID.value
        assertNotNull(ssid)
    }
} 