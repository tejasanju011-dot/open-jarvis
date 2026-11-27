package com.openjarvis.graphify

import java.security.MessageDigest

/**
 * Pure analysis algorithms kept separate so they can be tested on the JVM
 * without Android Context or a Room database.
 */
internal object AnalysisAlgorithms {

    fun buildSequences(commands: List<String>): List<String> {
        if (commands.size < 3) return emptyList()
        return (0 until commands.size - 2).map { index ->
            commands.subList(index, index + 3).joinToString(" → ")
        }
    }

    fun hashSequence(sequence: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sequence.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }
}
