import android.content.Context
import com.example.myapplication.bluetooth.BluetoothMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class BluetoothMonitorTest {

    private lateinit var bluetoothMonitor: BluetoothMonitor

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        bluetoothMonitor = BluetoothMonitor(context)
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