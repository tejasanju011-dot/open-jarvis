package com.openjarvis

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorLaunchSmokeTest {

    @Test
    fun jarvisAccessibilityServiceLaunchesAppAndVerifiesForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = "${context.packageName}/com.openjarvis.accessibility.JarvisAccessibilityService"

        // The emulator is disposable; enable Jarvis' real accessibility service through adb shell.
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services $component"
        ).close()
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure accessibility_enabled 1"
        ).close()

        val serviceDeadline = System.currentTimeMillis() + 15000L
        var service: JarvisAccessibilityService? = null
        while (System.currentTimeMillis() < serviceDeadline) {
            service = JarvisAccessibilityService.instance
            if (service != null) break
            Thread.sleep(250)
        }
        assertNotNull(
            "Jarvis AccessibilityService did not connect after enabling it in emulator settings",
            service
        )

        val packageName = "com.android.settings"
        assertNotNull(
            "Android Settings must be installed in the emulator",
            context.packageManager.getLaunchIntentForPackage(packageName)
        )

        assertTrue(
            "Jarvis AccessibilityService rejected the Settings launch request",
            service!!.openAppByPackage(packageName)
        )

        val foreground = runBlocking {
            service.waitForAppForeground(packageName, 10000L)
        }
        assertTrue(
            "Jarvis requested the launch but Settings never appeared in the accessibility foreground window",
            foreground
        )
    }
}
