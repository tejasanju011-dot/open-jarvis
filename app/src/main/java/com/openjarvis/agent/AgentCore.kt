
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
import com.openjarvis.builder.SelfCodeWorkspace
import com.openjarvis.diagnostics.DiagnosticLogger
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
    private val selfCodeWorkspace = SelfCodeWorkspace(context)

    private val _selfCodeProposal = MutableStateFlow<SelfCodeWorkspace.Proposal?>(null)
    val selfCodeProposal: StateFlow<SelfCodeWorkspace.Proposal?> = _selfCodeProposal
    private val _selfCodeStatus = MutableStateFlow("Self-code workspace ready")
    val selfCodeStatus: StateFlow<String> = _selfCodeStatus

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
                var stage = "starting"
                DiagnosticLogger.info(context, "Agent command execution started")
                try {
                    stage = "sanitizing command"
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

                    val sanitizedCommand = when (sanitized) {
                        is PromptSanitizer.SanitizeResult.Suspicious -> sanitized.sanitized
                        is PromptSanitizer.SanitizeResult.Clean -> sanitized.text
                        else -> cleanCommand
                    }
                    // "Go back to YouTube" means navigate to YouTube, not Android Back.
                    val commandToRun = normalizeAppNavigationCommand(sanitizedCommand)

                    stage = "analyzing task"
                    DiagnosticLogger.info(context, "Agent stage: $stage")
                    _state.value = AgentState.Running("Analyzing task...")

                    val plan = taskRouter.analyze(commandToRun)

                    stage = "reading screen"
                    DiagnosticLogger.info(context, "Agent stage: $stage")
                    _state.value = AgentState.Running("Reading screen...")

                    val screenText = withContext(Dispatchers.IO) {
                        getScreenReader()?.extractAllText() ?: ""
                    }

                    stage = "getting context"
                    DiagnosticLogger.info(context, "Agent stage: $stage")
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

                    stage = "requesting Groq completion"
                    DiagnosticLogger.info(context, "Agent stage: $stage; provider=${universalAdapter.getProviderName()}")
                    _state.value = AgentState.Running("Thinking...")

                    val startTime = System.currentTimeMillis()

                    val result = universalAdapter.complete(
                        fullSystem,
                        commandToRun
                    )

                    result.fold(
                        onSuccess = { rawJson ->
                            stage = "validating model response"
                            DiagnosticLogger.info(context, "Groq completion returned; validating response")

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

                            executeActions(actions, commandToRun)

                            if (_state.value !is AgentState.Error) {
                                graphifyRepo.logTask(
                                    command = commandToRun,
                                    result = "success",
                                    provider = universalAdapter.getProviderName(),
                                    latencyMs = latency
                                )

                                analysisEngine.analyzeLastTask()

                                val summary = workingMemory.get("summary")
                                    ?: workingMemory.get("result")
                                _state.value = AgentState.Done(
                                    summary?.takeIf { it.isNotBlank() }
                                        ?: "Done in ${latency}ms"
                                )
                            }
                        },

                        onFailure = { error ->
                            DiagnosticLogger.error(
                                context,
                                "Groq completion failed: type=${error.javaClass.simpleName}, message=${error.message ?: "<no message>"}"
                            )

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
                                    error.message ?: "Request failed (${error.javaClass.simpleName})"
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
                    DiagnosticLogger.error(
                        context,
                        "Agent failed at stage=$stage: type=${e.javaClass.simpleName}, message=${e.message ?: "<no message>"}",
                        e
                    )
                    _state.value = AgentState.Error(
                        e.message ?: "Agent failed at $stage (${e.javaClass.simpleName})"
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

    /** Generate a replacement source proposal; never execute or auto-apply it. */
    fun proposeCodeFix(relativePath: String, sourceCode: String, issueDescription: String) {
        scope.launch {
            if (sourceCode.isBlank() || issueDescription.isBlank()) { _selfCodeStatus.value = "Provide source code and describe the error."; return@launch }
            if (sourceCode.length > 120_000 || issueDescription.length > 4_000) { _selfCodeStatus.value = "Input too large. Keep source under 120,000 characters."; return@launch }
            _selfCodeStatus.value = "Analyzing source and preparing a fix..."
            DiagnosticLogger.info(context, "Self-code proposal requested for path=$relativePath")
            val system = """
You are Jarvis's restricted code-repair assistant.
Return ONLY the complete replacement contents for the one supplied source file.
Preserve language, package, imports, public APIs and existing behavior unless the bug requires a change.
Do not add dependencies, network calls, shell execution, reflection, dynamic code loading, secrets, permissions,
manifest/build-script changes, or code that modifies files outside the app's private proposal workspace.
Do not claim the code was compiled or tested. If source is insufficient, return the original source unchanged.
""".trimIndent()
            val user = """
ISSUE:
${issueDescription.take(4000)}

SOURCE PATH:
$relativePath

CURRENT SOURCE:
${sourceCode.take(120000)}
""".trimIndent()
            universalAdapter.complete(system, user).fold(
                onSuccess = { raw ->
                    val proposed = raw.trim().removePrefix("```kotlin").removePrefix("```java").removePrefix("```").removeSuffix("```").trim()
                    try {
                        val proposal = selfCodeWorkspace.createProposal(relativePath, proposed)
                        _selfCodeProposal.value = proposal
                        _selfCodeStatus.value = "Proposal ready (${proposal.sha256.take(12)}). Review before exporting."
                        DiagnosticLogger.info(context, "Self-code proposal created id=${proposal.id} path=${proposal.relativePath}")
                    } catch (e: Exception) {
                        _selfCodeStatus.value = "Proposal rejected: ${e.message ?: "unsafe path or invalid content"}"
                        DiagnosticLogger.warn(context, "Self-code proposal rejected: ${e.message}")
                    }
                },
                onFailure = { error ->
                    _selfCodeStatus.value = "Could not generate proposal: ${error.message ?: "AI request failed"}"
                    DiagnosticLogger.error(context, "Self-code generation failed: ${error.message}")
                }
            )
        }
    }

    /** Export only after the user explicitly approves in the review UI. */
    fun approveSelfCodeProposal(id: String): Result<String> = runCatching {
        val current = _selfCodeProposal.value ?: error("There is no proposal to approve")
        require(current.id == id) { "Proposal changed; review the latest proposal" }
        val output = selfCodeWorkspace.approveAndExport(id, userApproved = true)
        _selfCodeStatus.value = "Approved export saved privately: ${output.name}. It has not been applied or executed."
        DiagnosticLogger.info(context, "User approved self-code proposal export id=$id")
        output.absolutePath
    }

    fun rejectSelfCodeProposal(id: String) {
        if (_selfCodeProposal.value?.id == id) {
            _selfCodeProposal.value = null
            _selfCodeStatus.value = "Proposal rejected by user."
            DiagnosticLogger.info(context, "User rejected self-code proposal id=$id")
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

    private suspend fun executeActions(actions: List<Action>, command: String) {

        for ((index, action) in actions.withIndex()) {

            _state.value = AgentState.Running(
                "Action ${index + 1}/${actions.size}"
            )

            val service = JarvisAccessibilityService.instance

            when (action.action) {

                Action.OPEN_APP -> {
                    var packageName = action.packageName
                        ?: action.label?.let { findPackageByLabel(it) }

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

                    var launched = service.openAppByPackage(packageName)
                    // The model may return an outdated or incorrect package name.
                    // Retry using the installed launcher app matching its visible label.
                    if (!launched && !action.label.isNullOrBlank()) {
                        val resolvedPackage = findPackageByLabel(action.label!!)
                        if (resolvedPackage != null && resolvedPackage != packageName) {
                            packageName = resolvedPackage
                            launched = service.openAppByPackage(packageName)
                        }
                    }
                    if (!launched) {
                        DiagnosticLogger.error(
                            context,
                            "App launch failed: package=$packageName, label=${action.label ?: "<unknown>"}"
                        )
                        _state.value = AgentState.Error(
                            "Could not open ${action.label ?: packageName}. Check that the app is installed and try again."
                        )
                        return
                    }

                    DiagnosticLogger.info(
                        context,
                        "App launch request accepted; verifying foreground package=$packageName"
                    )
                    val reachedForeground = service.waitForAppForeground(packageName)
                    if (!reachedForeground) {
                        DiagnosticLogger.error(
                            context,
                            "App did not reach foreground within timeout: package=$packageName"
                        )
                        _state.value = AgentState.Error(
                            "${action.label ?: packageName} launch was requested, but it did not appear on screen. Check Android restrictions and try again."
                        )
                        return
                    }
                    DiagnosticLogger.info(
                        context,
                        "App foreground verified: package=$packageName"
                    )
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

                    val targetText = action.text
                    if (targetText.isNullOrBlank()) {
                        _state.value = AgentState.Error("Tap action has no target text.")
                        return
                    }

                    // Self-healing retry: transient Android window/layout updates can
                    // make the first accessibility lookup miss. Retry once after refresh.
                    var tapped = service.tapByText(targetText)
                    if (!tapped) {
                        DiagnosticLogger.warn(
                            context,
                            "Self-healing: tap target not found; retrying once: $targetText"
                        )
                        delay(700)
                        tapped = service.tapByText(targetText)
                    }
                    if (!tapped) {
                        val visibleText = getScreenReader()?.extractAllText().orEmpty()
                        DiagnosticLogger.error(
                            context,
                            "Self-healing failed for tap target='$targetText'; visibleText=${visibleText.take(1200)}"
                        )
                        _state.value = AgentState.Error(
                            "I couldn't tap '$targetText'. I recorded the visible screen text for diagnosis."
                        )
                        return
                    }
                    // Allow the destination screen or search field to finish opening.
                    delay(900)
                }

                Action.TYPE -> {
                    if (service == null) {
                        _state.value = AgentState.Error(
                            "Accessibility service is not enabled"
                        )
                        return
                    }

                    val textToType = action.value
                    if (textToType.isNullOrEmpty()) {
                        _state.value = AgentState.Error("Type action has no text.")
                        return
                    }
                    var typed = service.typeText(textToType)
                    if (!typed) {
                        DiagnosticLogger.warn(
                            context,
                            "Self-healing: editable field rejected text; retrying once"
                        )
                        delay(500)
                        typed = service.typeText(textToType)
                    }
                    if (!typed) {
                        val visibleText = getScreenReader()?.extractAllText().orEmpty()
                        DiagnosticLogger.error(
                            context,
                            "Self-healing failed to type; visibleText=${visibleText.take(1200)}"
                        )
                        _state.value = AgentState.Error(
                            "I couldn't enter the text. I recorded the visible screen text for diagnosis."
                        )
                        return
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

                    val isSummarization = command.contains("summar", ignoreCase = true) ||
                        command.contains("tl;dr", ignoreCase = true)

                    if (isSummarization) {
                        // Summaries should be returned inside Jarvis. Do not hand
                        // the task off to the external ChatGPT app.
                        val pageText = workingMemory.get("page_text")
                            ?: workingMemory.get("screen_text")
                            ?: getScreenReader()?.extractAllText().orEmpty()
                        val summaryPrompt = buildString {
                            append("Summarize the following on-screen/page content in response to the user's request. ")
                            append("Use concise, factual language. If the content is empty, say that no readable page content was found.\n\n")
                            append("USER REQUEST: ")
                            append(prompt.ifBlank { command })
                            append("\n\nPAGE CONTENT:\n")
                            append(pageText.take(12000))
                        }
                        val summaryResult = universalAdapter.complete(
                            "You are Jarvis. Summarize only the supplied page content. Do not ask the user to open another app. Do not invent facts.",
                            summaryPrompt
                        )
                        val summary = summaryResult.getOrElse { error ->
                            _state.value = AgentState.Error(
                                "Could not summarize the page: ${error.message ?: "AI request failed"}"
                            )
                            return
                        }
                        workingMemory.set(outputKey ?: "summary", summary)
                        workingMemory.set("summary", summary)
                    } else if (packageName != null) {
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

    private fun normalizeAppNavigationCommand(command: String): String {
        val match = Regex("""(?i)^\s*(?:please\s+)?go\s+back\s+to\s+(.+?)\s*$""").matchEntire(command)
        val target = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return if (target.isNotBlank()) "open $target" else command
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
