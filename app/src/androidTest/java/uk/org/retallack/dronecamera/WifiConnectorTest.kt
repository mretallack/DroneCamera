package uk.org.retallack.dronecamera

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PatternMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiConnectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ssidPrefixIsHASAKEE() {
        assertEquals("HASAKEE", WifiConnector.SSID_PREFIX)
    }

    @Test
    fun disconnectOnFreshInstanceDoesNotThrow() {
        val connector = WifiConnector(context)
        connector.disconnect() // Should not throw
    }

    @Test
    fun disconnectCanBeCalledMultipleTimes() {
        val connector = WifiConnector(context)
        connector.disconnect()
        connector.disconnect()
    }
}
