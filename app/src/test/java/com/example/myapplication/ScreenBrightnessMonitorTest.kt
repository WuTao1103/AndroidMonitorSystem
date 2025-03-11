import android.content.Context
import com.example.myapplication.screenbright.ScreenBrightnessMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ScreenBrightnessMonitorTest {

    private lateinit var screenBrightnessMonitor: ScreenBrightnessMonitor

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        screenBrightnessMonitor = ScreenBrightnessMonitor(context)
    }

    @Test
    fun testScreenBrightness() {
        val brightness = screenBrightnessMonitor.screenBrightness.value
        assertNotNull(brightness)
    }
} 