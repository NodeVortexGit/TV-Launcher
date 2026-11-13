package com.nodevortex.aura.util

import java.text.Normalizer

/** Search normalisation: lower-case, accent-free, Cyrillic transliterated to Latin. */
object Text {
    private val cyr = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sht", 'ъ' to "a", 'ь' to "", 'ю' to "yu", 'я' to "ya", 'ы' to "y",
        'э' to "e", 'і' to "i", 'ї' to "yi", 'є' to "ye",
    )
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^a-z0-9]+")

    fun norm(s: String): String {
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length + 8)
        for (ch in lower) {
            val t = cyr[ch]
            if (t != null) sb.append(t) else sb.append(ch)
        }
        val ascii = marks.replace(Normalizer.normalize(sb, Normalizer.Form.NFD), "")
        return nonWord.replace(ascii, " ").trim()
    }

    /** Collapse spelling variants so "smahnatiyat" ≈ "smahnatiat" and "professor" ≈ "profesor". */
    fun squash(token: String): String {
        val sb = StringBuilder(token.length)
        var prev = ' '
        for (ch in token) {
            val c = when (ch) { 'y' -> 'i'; 'w' -> 'v'; 'q' -> 'k'; 'c' -> 'k'; 'x' -> 'k'; else -> ch }
            if (c != prev) sb.append(c)
            prev = c
        }
        return sb.toString()
    }

    fun tokens(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    private val bufs = ThreadLocal.withInitial { arrayOf(IntArray(64), IntArray(64)) }

    /** Levenshtein distance with early exit; reuses per-thread buffers (called a lot by search). */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val pair = bufs.get()!!
        if (pair[0].size <= b.length) { pair[0] = IntArray(b.length + 16); pair[1] = IntArray(b.length + 16) }
        var prev = pair[0]; var cur = pair[1]
        for (j in 0..b.length) prev[j] = j
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Score how well [query] tokens match [target] tokens (both pre-normalised & squashed).
     * Returns 0 when nothing meaningful matches.
     */
    fun score(query: List<String>, target: List<String>): Float {
        if (query.isEmpty() || target.isEmpty()) return 0f
        var matched = 0f
        var strong = false
        for (q in query) {
            var best = 0f
            for (t in target) {
                val s = when {
                    t == q -> 1f
                    t.startsWith(q) && q.length >= 2 -> 0.85f
                    q.length >= 5 && t.length >= 5 && distance(q, t, if (q.length >= 8) 2 else 1) <= (if (q.length >= 8) 2 else 1) -> 0.7f
                    q.length >= 4 && t.contains(q) -> 0.55f
                    else -> 0f
                }
                if (s > best) best = s
            }
            if (best >= 0.7f && q.length >= 4) strong = true
            matched += best
        }
        val frac = matched / query.size
        if (frac >= 0.99f) return frac + 0.5f
        if (!strong && frac < 0.99f) return 0f
        return frac
    }
}
