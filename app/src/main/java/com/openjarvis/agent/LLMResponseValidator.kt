package com.openjarvis.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object LLMResponseValidator {
    
    private val knownActions = setOf(
        "open_app", "tap", "tap_coords", "long_press", "type",
        "clear_type", "swipe", "scroll", "press_back", "press_home",
        "press_recents", "wait_for", "screenshot", "read_screen",
        "ai_prompt", "mcp_call", "reply_notification",
        "read_clipboard", "write_clipboard", "highlight_element", "error"
    )
    
    fun validate(rawResponse: String): ValidationResult {
        val errors = mutableListOf<String>()
        
        val clean = rawResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        
        if (!clean.startsWith("[") && !clean.startsWith("{")) {
            errors.add("Response is not JSON")
            return ValidationResult(false, emptyList(), errors, false)
        }
        
        val parsed = try {
            if (clean.startsWith("{")) {
                JSONArray("[$clean]")
            } else {
                JSONArray(clean)
            }
        } catch (e: JSONException) {
            errors.add("Invalid JSON: ${e.message}")
            return ValidationResult(false, emptyList(), errors, false)
        }
        
        val actions = mutableListOf<Action>()
        
        for (i in 0 until parsed.length()) {
            val obj = try {
                parsed.getJSONObject(i)
            } catch (e: Exception) {
                errors.add("Item $i is not an object")
                continue
            }
            
            val actionType = obj.optString("action", "")
            if (actionType.isBlank()) {
                errors.add("Item $i missing 'action' field")
                continue
            }
            
            if (actionType !in knownActions) {
                errors.add("Unknown action type: $actionType — skipping")
                continue
            }
            
            actions.add(parseAction(obj))
        }
        
        if (actions.size > 50) {
            errors.add("Suspiciously long action plan (${actions.size} steps) — capped at 50")
            return ValidationResult(true, actions.take(50), errors, true)
        }
        
        return ValidationResult(
            isValid = actions.isNotEmpty(),
            actions = actions,
            errors = errors,
            wasRepaired = errors.isNotEmpty() && actions.isNotEmpty()
        )
    }
    
    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun parseAction(obj: JSONObject): Action {
        return Action(
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
    
    data class ValidationResult(
        val isValid: Boolean,
        val actions: List<Action>,
        val errors: List<String>,
        val wasRepaired: Boolean
    )
}