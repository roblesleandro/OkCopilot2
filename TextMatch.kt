package com.leandro.okcopilot

import java.text.Normalizer

object TextMatch {
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return n.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }

    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Qué tan bien aparece [phrase] dentro de [text] (0..1), tolerando errores del reconocedor. */
    fun phraseScore(text: String, phrase: String): Double {
        val t = normalize(text)
        val p = normalize(phrase)
        if (t.isEmpty() || p.isEmpty()) return 0.0
        if (" $t ".contains(" $p ")) return 1.0
        val words = t.split(" ")
        val n = p.split(" ").size
        val pc = p.replace(" ", "")
        var best = 0.0
        for (size in maxOf(1, n - 1)..(n + 1)) {
            for (i in 0..(words.size - size)) {
                val w = words.subList(i, i + size).joinToString("")
                best = maxOf(best, ratio(w, pc))
            }
        }
        return best
    }
}
