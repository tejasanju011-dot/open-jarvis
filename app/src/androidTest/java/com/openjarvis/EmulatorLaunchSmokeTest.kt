package com.openjarvis

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorLaunchSmokeTest {

    @Test
    fun androidCanLaunchSettingsAndReportItInForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage("com.android.settings")

        assertNotNull("Emulator Settings app must have a launch intent", launchIntent)

        launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        instrumentation.waitForIdleSync()

        val deadline = System.currentTimeMillis() + 8000L
        var foreground = false
        while (System.currentTimeMillis() < deadline) {
            val process = instrumentation.uiAutomation
                .executeShellCommand("dumpsys activity activities")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.close()
            if (output.contains("com.android.settings")) {
                foreground = true
                break
            }
            Thread.sleep(250)
        }

        assertTrue(
            "Settings launch was requested but package was not found in foreground activity state",
            foreground
        )
    }
}
