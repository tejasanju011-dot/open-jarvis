package com.openjarvis.bridge

import org.junit.Assert.*
import org.junit.Test

class SocketServerTest {

    @Test
    fun parseRequestWithValidJsonReturnsCommandAndRequestId() {
        val result = SocketProtocol.parseRequest("""{"cmd":"open chrome","requestId":"abc123"}""")
        assertNotNull(result)
        assertEquals("abc123", result?.first)
        assertEquals("open chrome", result?.second)
    }

    @Test
    fun parseRequestWithMissingCommandReturnsNull() {
        assertNull(SocketProtocol.parseRequest("""{"requestId":"abc123"}"""))
    }

    @Test
    fun parseRequestWithInvalidJsonReturnsNull() {
        assertNull(SocketProtocol.parseRequest("not json"))
    }

    @Test
    fun parseRequestWithMissingRequestIdUsesEmptyString() {
        val result = SocketProtocol.parseRequest("""{"cmd":"status"}""")
        assertNotNull(result)
        assertEquals("", result?.first)
        assertEquals("status", result?.second)
    }

    @Test
    fun escapeJsonEscapesSpecialCharacters() {
        val escaped = SocketProtocol.escapeJson("hello\nworld\"test\tvalue")
        assertTrue(escaped.contains("\\n"))
        assertTrue(escaped.contains("\\\""))
        assertTrue(escaped.contains("\\t"))
    }

    @Test
    fun createErrorResponseIncludesRequestIdAndError() {
        val response = SocketProtocol.createErrorResponse("req123", "test error")
        assertTrue(response.contains("req123"))
        assertTrue(response.contains("\"status\":\"error\""))
        assertTrue(response.contains("test error"))
    }

    @Test
    fun specialCommandsAreParsed() {
        val commands = listOf("status", "history", "memory", "providers")
        commands.forEachIndexed { index, command ->
            val result = SocketProtocol.parseRequest("""{"cmd":"$command","requestId":"r$index"}""")
            assertNotNull(result)
            assertEquals(command, result?.second)
            assertEquals("r$index", result?.first)
        }
    }

    @Test
    fun requestIdsArePreserved() {
        listOf("abc123", "xyz789", "test-id-001", "").forEach { id ->
            val json = if (id.isEmpty()) """{"cmd":"test"}"""
                else """{"cmd":"test","requestId":"$id"}"""
            val result = SocketProtocol.parseRequest(json)
            assertNotNull(result)
            assertEquals(id, result?.first)
        }
    }

    @Test
    fun malformedJsonReturnsNull() {
        listOf("", "{", """{"cmd":""", "null", "[][]").forEach { input ->
            assertNull("Should return null for: $input", SocketProtocol.parseRequest(input))
        }
    }
}
