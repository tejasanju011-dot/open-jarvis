
package com.openjarvis.agent

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.llm.UniversalAdapter
import kotlinx.coroutines.delay
import org.json.JSONObject

class SelfHealingExecutor(private val context: Context) {

    private val graphifyRepo = GraphifyRepository(context)
    private val llm = UniversalAdapter(context)

    private val maxAttempts = 3
    private val baseDelayMs = 1000L

    private fun getScreenReader(): ScreenReader? {
        val service = JarvisAccessibilityService.instance ?: return null
        return ScreenReader(service)
    }

    suspend fun executeWithHealing(
        action: Action,
        context: ExecutionContext,
        attempt: Int = 1
    ): ActionResult {

        val result = tryExecuteAction(action, context)

        if (result is ActionResult.Success) {
            return result
        }

        if (attempt >= maxAttempts) {
            return ActionResult.Failed(
                "Could not complete after $maxAttempts attempts"
            )
        }

        val currentScreen = getScreenReader()?.extractAllText() ?: ""

        val healingPrompt = buildHealingPrompt(
            action,
            context,
            currentScreen,
            attempt
        )

        val alternativeResponse = try {
            llm.complete(
                healingPrompt.first,
                healingPrompt.second
            ).getOrNull()
        } catch (e: Exception) {
            null
        }

        delay(baseDelayMs * attempt)

        if (!alternativeResponse.isNullOrBlank()) {
            val alternativeAction =
                parseActionFromLLM(alternativeResponse)

            if (alternativeAction != null) {
                return executeWithHealing(
                    alternativeAction,
                    context,
                    attempt + 1
                )
            }
        }

        return executeWithHealing(
            action,
            context,
            attempt + 1
        )
    }

    private fun tryExecuteAction(
        action: Action,
        executionContext: ExecutionContext
    ): ActionResult {

        return try {
            val service = JarvisAccessibilityService.instance
                ?: return ActionResult.Failed(
                    "Accessibility service is not available"
                )

            when (action.action) {

                Action.TAP -> {
                    val tapped = service.tapByText(
                        action.text ?: ""
                    )

                    if (tapped) {
                        ActionResult.Success("Tapped ${action.text}")
                    } else {
                        ActionResult.Failed("Tap failed")
                    }
                }

                Action.TYPE -> {
                    val typed = service.typeText(
                        action.value ?: ""
                    )

                    if (typed) {
                        ActionResult.Success("Typed text")
                    } else {
                        ActionResult.Failed("Typing failed")
                    }
                }

                Action.OPEN_APP -> {
                    val packageName = action.packageName

                    if (packageName.isNullOrBlank()) {
                        ActionResult.Failed("App package name is missing")
                    } else {
                        service.openAppByPackage(packageName)
                        ActionResult.Success("Opened $packageName")
                    }
                }

                Action.PRESS_BACK -> {
                    if (service.pressBack()) {
                        ActionResult.Success("Pressed back")
                    } else {
                        ActionResult.Failed("Could not press back")
                    }
                }

                Action.PRESS_HOME -> {
                    if (service.pressHome()) {
                        ActionResult.Success("Pressed home")
                    } else {
                        ActionResult.Failed("Could not press home")
                    }
                }

                Action.PRESS_RECENTS -> {
                    if (service.pressRecents()) {
                        ActionResult.Success("Opened recent apps")
                    } else {
                        ActionResult.Failed("Could not open recent apps")
                    }
                }

                else -> {
                    ActionResult.Failed(
                        "Unsupported action: ${action.action}"
                    )
                }
            }

        } catch (e: Exception) {
            ActionResult.Failed(
                e.message ?: "Unknown error"
            )
        }
    }

    private fun buildHealingPrompt(
        action: Action,
        executionContext: ExecutionContext,
        currentScreen: String,
        attempt: Int
    ): Pair<String, String> {

        val system = """
            You are a recovery assistant for an Android AI agent.

            Action failed: ${action.action}
            Expected state: ${executionContext.expectedState ?: "Task completion"}
            Current screen: ${currentScreen.take(500)}
            Attempt: $attempt/$maxAttempts

            Suggest one alternative action.
            Respond with a single Action JSON object.
        """.trimIndent()

        val user = """
            The action failed.

            Current screen:
            ${currentScreen.take(1000)}

            Suggest one alternative action as JSON.
        """.trimIndent()

        return system to user
    }

    private fun parseActionFromLLM(
        llmResponse: String
    ): Action? {

        return try {
            val cleanedResponse = llmResponse
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(cleanedResponse)

            Action(
                action = json.getString("action"),
                text = json.optString("text", null),
                value = json.optString("value", null),
                packageName = json.optString("package", null)
            )

        } catch (e: Exception) {
            null
        }
    }

    data class ExecutionContext(
        val expectedState: String?,
        val phaseId: String,
        val previousPhaseOutcome: String?,
        val screenBefore: String
    )

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class Failed(val reason: String) : ActionResult()
    }
}
