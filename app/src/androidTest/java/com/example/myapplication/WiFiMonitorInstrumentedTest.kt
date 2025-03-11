import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.MainActivity
import com.example.myapplication.wifi.WifiMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WiFiMonitorInstrumentedTest {

    private lateinit var wifiMonitor: WifiMonitor

    @Before
    fun setUp() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            wifiMonitor = WifiMonitor(context)
        }
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
