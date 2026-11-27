package com.openjarvis.llm.providers

import com.openjarvis.llm.ConnectionResult
import com.openjarvis.llm.HttpClient
import com.openjarvis.llm.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GroqProvider(
    private val apiKey: String,
    private val model: String = "llama-3.1-70b-versatile"
) : LLMProvider {
    
    override val name: String = "Groq"
    override val baseUrl: String = "https://api.groq.com/openai/v1"
    
    override suspend fun complete(
        systemPrompt: String,
        userMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
                put("max_tokens", 1024)
                put("temperature", 0.1)
                put("stream", false)
            }.toString()
            
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            HttpClient.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()

                if (!response.isSuccessful) {
                    val errorObject = json?.optJSONObject("error")
                    val detail = errorObject?.optString("message")
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: json?.optString("message")?.takeIf { !it.isNullOrBlank() }
                        ?: body.take(400).ifBlank { "No error details returned" }
                    throw Exception("Groq HTTP ${response.code}: $detail")
                }

                val choices = json?.optJSONArray("choices")
                    ?: throw Exception("Groq returned HTTP ${response.code} but no choices. Response: ${body.take(400)}")
                if (choices.length() == 0) {
                    throw Exception("Groq returned an empty choices list")
                }
                val message = choices.getJSONObject(0).optJSONObject("message")
                    ?: throw Exception("Groq response did not contain a message")
                message.optString("content").takeIf { it.isNotBlank() }
                    ?: throw Exception("Groq returned an empty message")
            }
        }
    }
    
    override suspend fun testConnection(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val startTime = System.currentTimeMillis()
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            
            HttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
            }
            System.currentTimeMillis() - startTime
        }
    }
}
