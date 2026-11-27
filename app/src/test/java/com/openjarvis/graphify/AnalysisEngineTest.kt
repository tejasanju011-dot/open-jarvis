package com.openjarvis.graphify

import org.junit.Assert.*
import org.junit.Test

class AnalysisEngineTest {

    @Test
    fun sequenceHashIsStableForSameInput() {
        val first = AnalysisAlgorithms.hashSequence("open whatsapp → call john → open whatsapp")
        val second = AnalysisAlgorithms.hashSequence("open whatsapp → call john → open whatsapp")
        assertEquals(first, second)
        assertEquals(16, first.length)
    }

    @Test
    fun differentSequencesProduceDifferentHashes() {
        val first = AnalysisAlgorithms.hashSequence("open whatsapp → call john")
        val second = AnalysisAlgorithms.hashSequence("open chrome → call john")
        assertNotEquals(first, second)
    }

    @Test
    fun levenshteinDistanceHandlesExactAndMisspelledNames() {
        assertEquals(0, AnalysisAlgorithms.levenshteinDistance("john", "john"))
        assertEquals(1, AnalysisAlgorithms.levenshteinDistance("john", "jon"))
        assertEquals(2, AnalysisAlgorithms.levenshteinDistance("john", "jhon"))
    }

    @Test
    fun levenshteinDistanceHandlesEmptyStrings() {
        assertEquals(0, AnalysisAlgorithms.levenshteinDistance("", ""))
        assertEquals(4, AnalysisAlgorithms.levenshteinDistance("john", ""))
        assertEquals(3, AnalysisAlgorithms.levenshteinDistance("", "jon"))
    }

    @Test
    fun buildSequencesCreatesConsecutiveTriples() {
        val result = AnalysisAlgorithms.buildSequences(listOf("open", "call", "message", "close"))
        assertEquals(
            listOf("open → call → message", "call → message → close"),
            result
        )
    }

    @Test
    fun buildSequencesReturnsEmptyWhenFewerThanThreeTasks() {
        assertTrue(AnalysisAlgorithms.buildSequences(listOf("open", "close")).isEmpty())
    }
}
