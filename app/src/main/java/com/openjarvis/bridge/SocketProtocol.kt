package com.openjarvis.bridge

/**
 * Pure JSON protocol helpers shared by the socket server and local JVM tests.
 */
internal object SocketProtocol {

    fun parseRequest(line: String): Pair<String, String>? {
        return try {
            val json = org.json.JSONObject(line)
            val command = json.optString("cmd", "")
            val requestId = json.optString("requestId", "")
            if (command.isBlank()) null else Pair(requestId, command)
        } catch (_: Exception) {
            null
        }
    }

    fun createErrorResponse(requestId: String, error: String): String {
        return """{"requestId":"${escapeJson(requestId)}","status":"error","result":"${escapeJson(error)}"}"""
    }

    fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
