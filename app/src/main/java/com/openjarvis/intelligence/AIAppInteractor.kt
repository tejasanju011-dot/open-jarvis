
package com.openjarvis.intelligence

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import kotlinx.coroutines.delay

class AIAppInteractor(private val context: Context) {

    private val workingMemory = TaskWorkingMemory()

    private fun getScreenReader(): ScreenReader? {
        val service = JarvisAccessibilityService.instance ?: return null
        return ScreenReader(service)
    }

    fun openAIApp(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(meta.packageName)
            ?: return false

        service.openAppByPackage(meta.packageName)
        return true
    }

    fun clearContext() {
        try {
            JarvisAccessibilityService.instance?.pressBack()
        } catch (e: Exception) {
            // Ignore accessibility errors
        }
    }

    fun typePrompt(prompt: String): Boolean {
        return JarvisAccessibilityService.instance
            ?.typeText(prompt) ?: false
    }

    suspend fun waitForResponse(timeoutMs: Long = 60_000): String {
        val startTime = System.currentTimeMillis()
        var lastText = ""
        var sameCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(1000)

            val currentText = getScreenReader()?.extractAllText() ?: return lastText

            if (currentText == lastText) {
                sameCount++

                if (sameCount >= 2 && currentText.isNotBlank()) {
                    return currentText
                }
            } else {
                sameCount = 0
                lastText = currentText
            }
        }

        return lastText
    }

    fun extractResponse(meta: AIAppMeta): String {
        val reader = getScreenReader() ?: return ""

        return when (meta.responseExtraction) {
            ResponseExtraction.SCREEN_TEXT -> {
                reader.extractAllText()
            }

            ResponseExtraction.OCR_REQUIRED -> {
                reader.extractAllText()
            }

            ResponseExtraction.COPY_BUTTON -> {
                tryCopyFromUI(reader)
            }

            ResponseExtraction.SHARE_MENU -> {
                tryShareFromUI(reader)
            }
        }
    }

    fun getWorkingMemory(): TaskWorkingMemory = workingMemory

    private fun tryCopyFromUI(reader: ScreenReader): String {
        return reader.extractAllText()
    }

    private fun tryShareFromUI(reader: ScreenReader): String {
        return reader.extractAllText()
    }
}
