import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.MainActivity
import com.example.myapplication.screenbright.ScreenBrightnessMonitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenBrightnessMonitorInstrumentedTest {

    private lateinit var screenBrightnessMonitor: ScreenBrightnessMonitor

    @Before
    fun setUp() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            screenBrightnessMonitor = ScreenBrightnessMonitor(context)
        }
    }

    @Test
    fun testScreenBrightness() {
        val brightness = screenBrightnessMonitor.screenBrightness.value
        assertNotNull(brightness)
    }
}
