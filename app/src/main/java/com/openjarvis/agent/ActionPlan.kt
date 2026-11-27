package com.openjarvis.agent

import org.json.JSONArray
import org.json.JSONObject

data class Action(
    val action: String,
    val packageName: String? = null,
    val label: String? = null,
    val text: String? = null,
    val value: String? = null,
    val hint: String? = null,
    val direction: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val distance: String? = null,
    val timeoutMs: Long = 3000L,
    val message: String? = null,
    val prompt: String? = null,
    val outputKey: String? = null
) {
    companion object {
        const val OPEN_APP = "open_app"
        const val TAP = "tap"
        const val TAP_COORDS = "tap_coords"
        const val TYPE = "type"
        const val CLEAR_TYPE = "clear_type"
        const val LONG_PRESS = "long_press"
        const val SWIPE = "swipe"
        const val SCROLL = "scroll"
        const val PRESS_BACK = "press_back"
        const val PRESS_HOME = "press_home"
        const val PRESS_RECENTS = "press_recents"
        const val WAIT_FOR = "wait_for"
        const val SCREENSHOT = "screenshot"
        const val READ_SCREEN = "read_screen"
        const val AI_PROMPT = "ai_prompt"
        const val EXTRACT_TEXT = "extract_text"
        const val ERROR = "error"
    }
}

sealed class AgentState {
    object Idle : AgentState()
    data class Running(val step: String) : AgentState()
    data class Done(val result: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

object ActionJsonParser {
    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    fun parse(json: String): List<Action>? {
        return try {
            val clean = json
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val jsonArray = JSONArray(clean)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                Action(
                    action = obj.getString("action"),
                    packageName = optionalString(obj, "package"),
                    label = optionalString(obj, "label"),
                    text = optionalString(obj, "text"),
                    value = optionalString(obj, "value"),
                    hint = optionalString(obj, "hint"),
                    direction = optionalString(obj, "direction"),
                    x = if (obj.has("x")) obj.getInt("x") else null,
                    y = if (obj.has("y")) obj.getInt("y") else null,
                    distance = optionalString(obj, "distance"),
                    timeoutMs = obj.optLong("timeout_ms", 3000L),
                    message = optionalString(obj, "message"),
                    prompt = optionalString(obj, "prompt"),
                    outputKey = optionalString(obj, "outputKey")
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}