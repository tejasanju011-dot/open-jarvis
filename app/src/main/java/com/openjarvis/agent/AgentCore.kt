
package com.openjarvis.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import com.openjarvis.graphify.AnalysisEngine
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.intelligence.AIAppInteractor
import com.openjarvis.intelligence.AIApps
import com.openjarvis.intelligence.AppAnalyzer
import com.openjarvis.intelligence.TaskRouter
import com.openjarvis.intelligence.TaskWorkingMemory
import com.openjarvis.llm.UniversalAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AgentCore(private val context: Context) {

    private val graphifyRepo = GraphifyRepository(context)
    private val analysisEngine = AnalysisEngine(context)
    private val universalAdapter = UniversalAdapter(context)
    private val taskRouter = TaskRouter(context)
    private val appAnalyzer = AppAnalyzer(context)
    private val aiAppInteractor = AIAppInteractor(context)

    private var workingMemory = TaskWorkingMemory()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val taskMutex = Mutex()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    private val systemPrompt = """
You are Open Jarvis — an Android device control AI agent.
The user gives you a command in natural language.
Respond with ONLY a valid JSON array of actions.

AVAILABLE ACTIONS:
open_app → {"action":"open_app","package":"com.package","label":"AppName"}
tap → {"action":"tap","text":"Button text"}
type → {"action":"type","value":"text to type"}
press_back → {"action":"press_back"}
press_home → {"action":"press_home"}
press_recents → {"action":"press_recents"}
ai_prompt → {"action":"ai_prompt","package":"com.openai.chatgpt","prompt":"prompt","outputKey":"result"}
extract_text → {"action":"extract_text","outputKey":"page_text"}
error → {"action":"error","message":"reason"}

CURRENT SCREEN CONTENT: {SCREEN_OCR}
APP SELECTION REASONING: {APP_REASONING}
INSTALLED AI APPS: {AI_APPS}
RECENT MEMORY CONTEXT: {GRAPHIFY_CONTEXT}

RULES:
- Always verify the screen before acting.
- Keep action arrays short.
- Return JSON only.
""".trimIndent()

    private fun getScreenReader(): ScreenReader? {
        val service = JarvisAccessibilityService.instance ?: return null
        return ScreenReader(service)
    }

    fun executeTask(cleanCommand: String) {
        workingMemory = TaskWorkingMemory()

        scope.launch {
            taskMutex.withLock {
                try {
                    val sanitized = PromptSanitizer.sanitize(cleanCommand)

                    when (sanitized) {
                        is PromptSanitizer.SanitizeResult.Rejected -> {
                            _state.value = AgentState.Error(sanitized.reason)
                            return@withLock
                        }

                        is PromptSanitizer.SanitizeResult.Suspicious -> {
                            _state.value = AgentState.Running("Analyzing command...")
                        }

                        is PromptSanitizer.SanitizeResult.Clean -> {}
                    }

                    val commandToRun = when (sanitized) {
                        is PromptSanitizer.SanitizeResult.Suspicious ->
                            sanitized.sanitized

                        is PromptSanitizer.SanitizeResult.Clean ->
                            sanitized.text

                        else -> cleanCommand
                    }

                    _state.value = AgentState.Running("Analyzing task...")

                    val plan = taskRouter.analyze(commandToRun)

                    _state.value = AgentState.Running("Reading screen...")

                    val screenText = withContext(Dispatchers.IO) {
                        getScreenReader()?.extractAllText() ?: ""
                    }

                    _state.value = AgentState.Running("Getting context...")

                    val memoryContext =
                        graphifyRepo.buildMemoryContext(commandToRun)

                    val fullSystem = systemPrompt
                        .replace("{SCREEN_OCR}", screenText.take(2000))
                        .replace("{APP_REASONING}", plan.reasoning)
                        .replace("{AI_APPS}", getInstalledAIApps())
                        .replace(
                            "{GRAPHIFY_CONTEXT}",
                            if (memoryContext.isBlank()) {
                                "No recent tasks"
                            } else {
                                memoryContext
                            }
                        )

                    _state.value = AgentState.Running("Thinking...")

                    val startTime = System.currentTimeMillis()

                    val result = universalAdapter.complete(
                        fullSystem,
                        commandToRun
                    )

                    result.fold(
                        onSuccess = { rawJson ->

                            val latency =
                                System.currentTimeMillis() - startTime

                            val validation =
                                LLMResponseValidator.validate(rawJson)

                            if (!validation.isValid) {
                                _state.value = AgentState.Error(
                                    "Invalid response: ${
                                        validation.errors.firstOrNull()
                                            ?: "Unknown validation error"
                                    }"
                                )

                                graphifyRepo.logTask(
                                    commandToRun,
                                    "failed: validation error",
                                    "",
                                    0L
                                )

                                return@fold
                            }

                            var actions = ActionJsonParser.parse(rawJson)

                            if (actions == null) {
                                val retry = universalAdapter.complete(
                                    fullSystem,
                                    "$commandToRun\nRespond with JSON array only."
                                )

                                actions = retry.getOrNull()?.let {
                                    ActionJsonParser.parse(it)
                                }
                            }

                            if (actions == null) {
                                _state.value = AgentState.Error(
                                    "Could not parse AI response"
                                )

                                graphifyRepo.logTask(
                                    commandToRun,
                                    "failed: parse error",
                                    "",
                                    0L
                                )

                                return@fold
                            }

                            _state.value = AgentState.Running(
                                "Executing ${actions.size} actions..."
                            )

                            executeActions(actions)

                            if (_state.value !is AgentState.Error) {
                                graphifyRepo.logTask(
                                    command = commandToRun,
                                    result = "success",
                                    provider = universalAdapter.getProviderName(),
                                    latencyMs = latency
                                )

                                analysisEngine.analyzeLastTask()

                                _state.value = AgentState.Done(
                                    "Done in ${latency}ms"
                                )
                            }
                        },

                        onFailure = { error ->

                            val msg = when {
                                error.message?.contains("401") == true ->
                                    "Invalid API key"

                                error.message?.contains("429") == true ->
                                    "Rate limited — wait a moment"

                                error.message?.contains("timeout") == true ->
                                    "Request timed out"

                                error.message?.contains("Unable to resolve") == true ->
                                    "Network error — check connection"

                                else ->
                                    error.message ?: "Unknown error"
                            }

                            _state.value = AgentState.Error(msg)

                            graphifyRepo.logTask(
                                commandToRun,
                                "failed: $msg",
                                "",
                                0L
                            )
                        }
                    )

                } catch (e: Exception) {

                    _state.value = AgentState.Error(
                        e.message ?: "Unknown error"
                    )

                    graphifyRepo.logTask(
                        cleanCommand,
                        "failed: ${e.message}",
                        "",
                        0L
                    )
                }
            }
        }
    }

    suspend fun testConnection(): Result<Long> {
        return universalAdapter.testConnection()
    }

    fun getCurrentProviderName(): String {
        return universalAdapter.getProviderName()
    }

    fun getStateFlow(): StateFlow<AgentState> = state

    private fun getInstalledAIApps(): String {
        return AIApps.KNOWN_AI_APPS.keys.joinToString(", ")
    }

    suspend fun getAnalyzedAppCount(): Int {
        return appAnalyzer.getAnalyzedCount()
    }

    suspend fun getAIAppCount(): Int {
        return appAnalyzer.getAICount()
    }

    private suspend fun executeActions(actions: List<Action>) {

        for ((index, action) in actions.withIndex()) {

            _state.value = AgentState.Running(
                "Action ${index + 1}/${actions.size}"
            )

            val service = JarvisAccessibilityService.instance

            when (action.action) {

                Action.OPEN_APP -> {
                    val packageName = action.packageName
                        ?: action.label?.let {
                            findPackageByLabel(it)
                        }

                    if (packageName == null) {
                        _state.value = AgentState.Error(
                            "App not found: ${action.label ?: "Unknown"}"
                        )
                        return
                    }

                    if (service == null) {
                        _state.value = AgentState.Error(
                            "Accessibility service is not enabled"
                        )
                        return
                    }

                    service.openAppByPackage(packageName)

                    graphifyRepo.logAppOpened(
                        packageName,
                        action.label ?: packageName
                    )
                }

                Action.TAP -> {
                    if (service == null) {
                        _state.value = AgentState.Error(
                            "Accessibility service is not enabled"
                        )
                        return
                    }

                    action.text?.let {
                        service.tapByText(it)
                    }
                }

                Action.TYPE -> {
                    if (service == null) {
                        _state.value = AgentState.Error(
                            "Accessibility service is not enabled"
                        )
                        return
                    }

                    action.value?.let {
                        service.typeText(it)
                    }
                }

                Action.PRESS_BACK -> {
                    service?.pressBack()
                }

                Action.PRESS_HOME -> {
                    service?.pressHome()
                }

                Action.PRESS_RECENTS -> {
                    service?.pressRecents()
                }

                Action.AI_PROMPT -> {

                    val packageName = action.packageName
                    val prompt = workingMemory.interpolate(
                        action.prompt ?: ""
                    )

                    val outputKey = action.outputKey

                    if (packageName != null) {
                        val meta = AIApps.KNOWN_AI_APPS[packageName]

                        if (meta != null) {
                            aiAppInteractor.openAIApp(meta)

                            delay(2000)

                            aiAppInteractor.typePrompt(prompt)

                            delay(1000)

                            val response =
                                aiAppInteractor.waitForResponse()

                            if (outputKey != null) {
                                workingMemory.set(outputKey, response)
                            }
                        }
                    }
                }

                Action.EXTRACT_TEXT -> {

                    val outputKey =
                        action.outputKey ?: "page_text"

                    val text = getScreenReader()?.extractAllText() ?: ""

                    workingMemory.set(outputKey, text)
                }

                Action.ERROR -> {
                    _state.value = AgentState.Error(
                        action.message ?: "Task failed"
                    )
                    return
                }

                else -> {
                    _state.value = AgentState.Error(
                        "Unsupported action: ${action.action}"
                    )
                    return
                }
            }

            delay(500)
        }
    }

    private fun findPackageByLabel(label: String): String? {

        val pm = context.packageManager

        val intent = Intent(
            Intent.ACTION_MAIN,
            null
        ).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps: List<ResolveInfo> =
            pm.queryIntentActivities(intent, 0)

        val normalizedLabel = label.lowercase().trim()

        for (app in apps) {
            val appLabel =
                app.loadLabel(pm).toString().lowercase()

            if (
                appLabel == normalizedLabel ||
                appLabel.contains(normalizedLabel) ||
                normalizedLabel.contains(appLabel)
            ) {
                return app.activityInfo.packageName
            }
        }

        return null
    }
}
