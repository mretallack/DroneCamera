package uk.org.retallack.dronecamera

import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun buttonIsEnabledOnLaunch() {
        activityRule.scenario.onActivity { activity ->
            val btn = activity.findViewById<Button>(R.id.toggleButton)
            assertTrue(btn.isEnabled)
        }
    }

    @Test
    fun statusShowsIdleOnLaunch() {
        activityRule.scenario.onActivity { activity ->
            val status = activity.findViewById<TextView>(R.id.statusText)
            assertEquals(activity.getString(R.string.status_idle), status.text.toString())
        }
    }

    @Test
    fun buttonShowsStartOnLaunch() {
        activityRule.scenario.onActivity { activity ->
            val btn = activity.findViewById<Button>(R.id.toggleButton)
            assertEquals(activity.getString(R.string.btn_start), btn.text.toString())
        }
    }
}
