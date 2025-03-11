import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.MainActivity
import com.example.myapplication.bluetooth.BluetoothMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothMonitorInstrumentedTest {

    private lateinit var bluetoothMonitor: BluetoothMonitor

    @Before
    fun setUp() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            bluetoothMonitor = BluetoothMonitor(context)
        }
    }

    @Test
    fun testBluetoothState() {
        val isEnabled = bluetoothMonitor.bluetoothState.value
        assertNotNull(isEnabled)
    }

    @Test
    fun testPairedDevices() {
        val pairedDevices = bluetoothMonitor.pairedDevices.value
        assertNotNull(pairedDevices)
    }
}
