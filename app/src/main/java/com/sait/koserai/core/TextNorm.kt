package com.sait.koserai.core

import java.util.Locale

object TextNorm {
    private val TR = Locale("tr")

    /** Küçük harf + Türkçe karakterleri sadeleştir + noktalama kaldır. "WhatsApp'ı Aç!" -> "whatsapp i ac" */
    fun basic(s: String): String {
        val low = s.lowercase(TR)
        val sb = StringBuilder(low.length)
        for (c in low) {
            sb.append(
                when (c) {
                    'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                    'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                    '\'', '’', '`' -> ' '
                    else -> if (c.isLetterOrDigit() || c == ' ') c else ' '
                }
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 arası benzerlik */
    fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    /** Türkçe çekim eklerini kabaca at: "ahmete" -> "ahmet", "whatsappa" -> "whatsapp" */
    fun stripSuffix(w: String): String {
        val suffixes = listOf(
            "ndan", "nden", "ndaki", "ndeki", "ından", "inden", "undan", "ünden",
            "dan", "den", "tan", "ten", "nda", "nde", "na", "ne", "ya", "ye", "yi", "yu", "yı",
            "da", "de", "ta", "te", "ni", "nu", "nı", "nü", "yla", "yle", "la", "le",
            "a", "e", "i", "u", "ı", "ü"
        )
        for (s in suffixes) {
            if (w.length > s.length + 2 && w.endsWith(s)) return w.dropLast(s.length)
        }
        return w
    }

    fun capitalizeTr(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(TR) else it.toString() }
}
